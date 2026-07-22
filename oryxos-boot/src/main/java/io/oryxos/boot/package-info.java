/**
 * Spring Boot bootstrap — executable entry point.
 *
 * <p>{@code OryxOsApplication} is the {@code public static void main} of the entire
 * project. Spring Boot's component scan reaches across all {@code io.oryxos.*} packages
 * so beans declared in any module are picked up automatically.
 *
 * <p>This module is the packaging target for the fat JAR: {@code mvn package} on
 * {@code oryxos-boot} produces {@code target/oryxos.jar} (executable via
 * {@code java -jar oryxos.jar}).
 */
package io.oryxos.boot;