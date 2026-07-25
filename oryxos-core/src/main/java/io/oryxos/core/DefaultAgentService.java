package io.oryxos.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * US-2 / US-5 stage: default implementation of {@link AgentService}.
 *
 * <h2>Responsibility chain</h2>
 * <ol>
 *   <li>Pre-validate Session / userMessage non-null (C-AS-7)</li>
 *   <li>Look up Session's referenced Profile from {@link ProfileRegistry} (C-AS-3 / C-AS-4)</li>
 *   <li>Set {@link ProfileContext} (C-AS-2)</li>
 *   <li>Call {@link ReActLoop#run(Profile, Session, String)}</li>
 *   <li>Finally clear {@code ProfileContext} (C-AS-5)</li>
 * </ol>
 *
 * <p>Exception-path design:
 * <ul>
 *   <li>{@link ProfileRegistry#find} returns empty -> throw {@link IllegalArgumentException} (C-AS-3);
 *       {@code ProfileContext} is not yet set, so no cleanup needed</li>
 *   <li>{@link ReActLoop#run} throws any exception -> finally block clears ProfileContext, then
 *       exception propagates upward (C-AS-5: exception path must not break thread-local state)</li>
 * </ul>
 */
@Service
public final class DefaultAgentService implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentService.class);

    private final ProfileRegistry profileRegistry;
    private final ReActLoop reactLoop;

    public DefaultAgentService(ProfileRegistry profileRegistry, ReActLoop reactLoop) {
        this.profileRegistry = Objects.requireNonNull(profileRegistry, "profileRegistry");
        this.reactLoop = Objects.requireNonNull(reactLoop, "reactLoop");
    }

    @Override
    public LoopResult process(Session session, String userMessage) {
        // C-AS-7: null validation
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(userMessage, "userMessage");

        // C-AS-3: resolve Profile BEFORE setting ProfileContext -- so fail-fast leaves no thread-local.
        // Note: C-AS-4 (provider configured check) is enforced by Profile's compact constructor invariant,
        // so it cannot be reached through normal Profile construction -- it is documented as a
        // defense-in-depth. We do not duplicate the check here.
        String profileName = session.profileName();
        Profile profile = profileRegistry.find(profileName)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown profile: '" + profileName + "' (registry has "
                    + profileRegistry.names().size() + " profiles)"));

        // C-AS-2: set ProfileContext -- finally MUST clear
        ProfileContext.Snapshot snapshot = new ProfileContext.Snapshot(
            profile.name(), session.id(), new AtomicInteger(0)
        );
        ProfileContext.set(snapshot);
        log.debug("agent.process start session_id={} profile={}", session.id(), profile.name());
        try {
            return reactLoop.run(profile, session, userMessage);
        } finally {
            ProfileContext.clear();
            // C-AS-2 defensive check: if anything tried to reset during the run, we'd see a leak
            Optional<ProfileContext.Snapshot> residual = ProfileContext.current();
            if (residual.isPresent()) {
                log.error("ProfileContext leaked after process(): {}", residual.get());
                ProfileContext.clear();
            }
        }
    }
}