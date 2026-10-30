package com.ratel.toolchain.project;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开发者工程的目录布局与 project.yaml 读写。
 *
 * <pre>
 * myproj/
 * ├── project.yaml          # 本工程配置
 * ├── origin.apk            # 原始 apk（不可变基准，重建时未变更条目 raw-copy）
 * ├── java/                 # java 混编源码区（整类替换 / 新增类）
 * ├── smali/                # smali 补丁区（只存按需 pull 并修改过的类）
 * ├── stubs/                # 生成的 compileOnly stub jar 与生成配置
 * └── out/                  # build 产物
 * </pre>
 */
public class ProjectLayout {

    public final Path root;
    public final Path originApk;
    /** split APK 集合（App Bundle 产物）：base.apk + split*.apk；单 apk 工程此目录仅有 base.apk。 */
    public final Path splitsDir;
    /** java 混编源码区（标准 gradle source set，IDE 可导航）。 */
    public final Path patchSourceDir;
    /** 旧版源码区（java/ 兼容路径，build 兜底编译）。 */
    public final Path javaDir;
    public final Path smaliDir;
    public final Path stubsDir;
    public final Path outDir;
    /** gradle ratelPatchDex 任务的产物目录（patch dex 已完成 relocate+d8）。 */
    public final Path patchDexDir;

    /**
     * stub bytes 以内容 hash 命名落 libs/（ratel-stubs-<hash>.jar，ratel-stubs- 前缀特征
     * 识别并清理旧版本）。文件名随内容变化——gradle 输入与 IDE 索引必然感知，
     * 规避"路径不变内容变"的缓存陷阱。
     */
    public Path writeStubJar(byte[] content) throws java.io.IOException {
        java.io.File dir = stubsDir.toFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new java.io.IOException("无法创建 stub 目录: " + dir);
        }
        String digest;
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(content);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            digest = sb.substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new java.io.IOException(e);
        }
        java.io.File out = new java.io.File(dir, "ratel-stubs-" + digest + ".jar");
        java.io.File[] old = dir.listFiles((dd, n) -> n.startsWith("ratel-stubs-") && n.endsWith(".jar"));
        boolean changed = !out.isFile();
        java.nio.file.Files.write(out.toPath(), content);
        if (old != null) {
            for (java.io.File f : old) {
                if (!f.getName().equals(out.getName())) {
                    f.delete();
                }
            }
        }
        System.out.println(changed ? "stub 已更新: " + out.getName() + "（建议 IDE sync 刷新索引）"
                : "stub 无变化: " + out.getName());
        return out.toPath();
    }

    /** libs 下的 stub jar（内容 hash 命名，唯一）；无则 null。 */
    public Path findStubJar() {
        File dir = stubsDir.toFile();
        File[] jars = dir != null && dir.isDirectory()
                ? dir.listFiles((d, n) -> n.startsWith("ratel-stubs-") && n.endsWith(".jar"))
                : null;
        return jars != null && jars.length > 0 ? jars[0].toPath() : null;
    }

    public ProjectLayout(Path root) {
        this.root = root;
        this.originApk = root.resolve("origin.apk");
        this.splitsDir = root.resolve("origin-splits");
        this.patchSourceDir = root.resolve("app-patch/src/main/java");
        this.javaDir = root.resolve("java");
        this.smaliDir = root.resolve("smali");
        // stub 仅为 app-patch 服务：产出即 app-patch/libs/（内容 hash 命名，见 StubCommand）
        this.stubsDir = root.resolve("app-patch/libs");
        this.outDir = root.resolve("out");
        this.patchDexDir = root.resolve("build/ratel/patch-dexes");
    }

    public static ProjectLayout load(Path projectRoot) {
        ProjectLayout layout = new ProjectLayout(projectRoot.toAbsolutePath().normalize());
        if (!Files.isRegularFile(layout.root.resolve("project.yaml"))) {
            throw new IllegalArgumentException("not a ratel project (project.yaml missing): " + layout.root);
        }
        return layout;
    }

    public void createDirectories() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(patchSourceDir);
        Files.createDirectories(smaliDir);
        Files.createDirectories(outDir);
    }

    public Path projectYaml() {
        return root.resolve("project.yaml");
    }

    // ---- project.yaml 读写（snakeyaml，保持字段顺序，人可读可手改） ----

    private static final org.yaml.snakeyaml.Yaml YAML =
            new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.DumperOptions() {{
                setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
                setPrettyFlow(true);
            }});

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml() throws IOException {
        try (var in = Files.newInputStream(projectYaml())) {
            Object o = YAML.load(in);
            if (o == null) return new LinkedHashMap<>();
            return (Map<String, Object>) o;
        }
    }

    public Map<String, Object> config() throws IOException {
        return readYaml();
    }

    public String configString(String dottedKey, String def) throws IOException {
        Map<String, Object> map = readYaml();
        Object v = lookup(map, dottedKey.split("\\."));
        return v == null ? def : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static Object lookup(Map<String, Object> map, String[] path) {
        Object cur = map;
        for (int i = 0; i < path.length; i++) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(path[i]);
        }
        return cur;
    }

    public void writeConfig(Map<String, Object> config) throws IOException {
        // 签名密码明文存本地工程：这是本地开发证书，不是发布资产
        try (var writer = Files.newBufferedWriter(projectYaml())) {
            YAML.dump(config, writer);
        }
    }

    public static Map<String, Object> newDefaultConfig(String packageName, String versionName, long versionCode) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("package", packageName);
        config.put("versionName", versionName);
        config.put("versionCode", versionCode);

        Map<String, Object> entry = new LinkedHashMap<>();
        // 入口注入方案：clinit（不动 manifest） | appComponentFactory | applicationProxy。可随时切换。
        entry.put("scheme", "clinit");
        config.put("entry", entry);

        Map<String, Object> signing = new LinkedHashMap<>();
        signing.put("keystore", "ratel-dev.keystore");
        signing.put("alias", "ratel");
        signing.put("storePassword", "");
        config.put("signing", signing);

        Map<String, Object> stubs = new LinkedHashMap<>();
        stubs.put("includes", List.of());
        config.put("stubs", stubs);

        Map<String, Object> modules = new LinkedHashMap<>();
        modules.put("embed", null);   // 单模块模型：内嵌模块 apk 路径（相对工作区根）
        modules.put("dev", null);     // dev 插件包名（按包名直查已装 apk）
        // native hook 接线开关：so 始终构建打包（基础设施），此处控制运行时是否加载
        // （native watch/redirect 会操作 linker，敏感场景可关）
        modules.put("native", true);
        config.put("modules", modules);

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("version", "none");
        config.put("runtime", runtime);

        // crcBypass：app 自校验读 base.apk → fd 重定向到内嵌原始 apk（体积接近翻倍，默认关）
        config.put("crcBypass", false);
        return config;
    }
}
