package io.oryxos.tool.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Sandbox 配置 —— 从 {@code oryxos.tool.sandbox.*} 绑定。
 *
 * <p>007 阶段扩展为 4 类子配置：HTTP（既有）/ File（新增）/ Shell（新增）；详见
 * <a href="../../../../../../../specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md">specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md §12</a>。
 *
 * <p>fail-closed 默认（spec FR-011 / 宪法 §VII）：空白名单 = 全部拒绝；业务方未显式声明前不允许 Tool 调用。
 *
 * <p>配置示例（{@code .oryxos/application.yaml}）：
 * <pre>{@code
 * oryxos:
 *   tool:
 *     sandbox:
 *       http:
 *         allowed-domains:
 *           - qyapi.weixin.qq.com
 *           - oapi.dingtalk.com
 *           - open.feishu.cn
 *           - localhost              # 本地测试 / WireMock
 *       file:
 *         allowed-paths:
 *           - /home/agent/workspace  # 工作区根；子目录允许
 *       shell:
 *         allowed-commands:
 *           - git
 *           - ls
 *           - cat
 *         dangerous-commands:        # 兼容读源（008 阶段统一收敛）
 *           - rm
 *           - shutdown
 * }</pre>
 *
 * <p>详见 <a href="../../../../../../../specs/004-notify-channel/contracts/channel-config.md">specs/004-notify-channel/contracts/channel-config.md §4</a>
 * + <a href="../../../../../../../specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md">specs/007-sandbox-whitelist/contracts/sandbox-whitelist.md §12</a>。
 */
@ConfigurationProperties(prefix = "oryxos.tool.sandbox")
public class SandboxProperties {

    /** HTTP 出站白名单子配置（既有，005 落地）。 */
    private Http http = new Http();

    /** 文件读写白名单子配置（007 新增）。 */
    private File file = new File();

    /** Shell 命令白名单子配置（007 新增）。 */
    private Shell shell = new Shell();

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http == null ? new Http() : http;
    }

    public File getFile() {
        return file;
    }

    public void setFile(File file) {
        this.file = file == null ? new File() : file;
    }

    public Shell getShell() {
        return shell;
    }

    public void setShell(Shell shell) {
        this.shell = shell == null ? new Shell() : shell;
    }

    public static class Http {
        /** 允许的 host 后缀列表（小写；匹配 {@code URL.getHost()} 后缀） */
        private List<String> allowedDomains = List.of();

        public List<String> getAllowedDomains() {
            return allowedDomains;
        }

        public void setAllowedDomains(List<String> allowedDomains) {
            this.allowedDomains = allowedDomains == null ? List.of() : allowedDomains;
        }
    }

    /**
     * 文件读写白名单子配置 —— 007 阶段新增。
     *
     * <p>核心阶段假定业务方把"工作区根"作为 {@code allowed-paths} 第一项；任意子路径允许。
     * 严格前缀匹配防 {@code /home/agent/workspace} 被 {@code /home/agent/workspace-evil} 绕过
     * （spec FR-003 / research.md R-01）。
     */
    public static class File {
        /** 允许访问的路径前缀列表（如 {@code /home/agent/workspace}）；匹配相对路径 resolve 后的绝对路径。 */
        private List<String> allowedPaths = List.of();

        public List<String> getAllowedPaths() {
            return allowedPaths;
        }

        public void setAllowedPaths(List<String> allowedPaths) {
            this.allowedPaths = allowedPaths == null ? List.of() : allowedPaths;
        }
    }

    /**
     * Shell 命令白名单子配置 —— 007 阶段新增。
     *
     * <p>{@code allowedCommands} 经首 token 提取 + 大小写不敏感匹配（spec FR-004 / research.md R-02）。
     * {@code dangerousCommands} 是兼容读源 —— {@link io.oryxos.tool.shell.ShellTool}
     * 内的 {@code ShellToolProperties.dangerousCommands} 为主（既有黑名单兜底先于白名单）；
     * 本字段 007 阶段保留用于未来扩展阶段统一收敛（research.md R-06）。
     */
    public static class Shell {
        /** 允许的命令首 token 列表（lower-case 后精确匹配） */
        private List<String> allowedCommands = List.of();

        /** 兼容读源 —— 已知威胁黑名单（{@code ShellToolProperties.dangerousCommands} 优先；008 阶段统一收敛） */
        private List<String> dangerousCommands = List.of();

        public List<String> getAllowedCommands() {
            return allowedCommands;
        }

        public void setAllowedCommands(List<String> allowedCommands) {
            this.allowedCommands = allowedCommands == null ? List.of() : allowedCommands;
        }

        public List<String> getDangerousCommands() {
            return dangerousCommands;
        }

        public void setDangerousCommands(List<String> dangerousCommands) {
            this.dangerousCommands = dangerousCommands == null ? List.of() : dangerousCommands;
        }
    }
}