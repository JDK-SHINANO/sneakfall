package me.shinano.sneakfall;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.ConfigValue;
import org.slf4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * SneakFall 配置类。
 * <p>
 * 基于 NeoForge 的 {@link ModConfigSpec} 定义配置结构；生成的 TOML 配置文件会自带
 * 本类中编写的全部中文注释，可直接阅读与修改。所有配置值均在事件触发时动态读取，
 * 绝不在类静态初始化阶段读取（此时 config 尚未加载）。
 * </p>
 */
public final class SneakFallConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 潜行减免的触发模式。 */
    public enum Mode {
        /** 落地瞬间按住潜行即减免（持续保持潜行状态即可）。 */
        SUSTAIN,
        /** 按下潜行后，在时间窗口内落地才减免（窗口外落地不减免）。 */
        QTE
    }

    /** 伤害减免的计算方式。 */
    public enum ReductionMode {
        /** 减免等效坠落格数：从实际坠落距离中扣除该格数后再计算伤害。 */
        DISTANCE,
        /** 按比例减免伤害：最终伤害 = 原始伤害 × (1 - 比例)。 */
        PERCENT
    }

    /** 配置构建器：static 块中完成全部定义后调用 build()。 */
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---- 保底配置字段（仅保存句柄，不缓存值；每次 buildFallback() 时现读现值） ----
    private static final ConfigValue<Mode> FALLBACK_MODE;
    private static final ConfigValue<Double> FALLBACK_QTE_WINDOW;
    private static final ConfigValue<ReductionMode> FALLBACK_REDUCTION_MODE;
    private static final ConfigValue<Double> FALLBACK_REDUCTION_VALUE;

    // ---- 阶段配置字段：5 个等长平行列表（TOML 中表现为 [stages] 分组下的 5 行平行数组） ----
    // 说明：NeoForge ModConfigSpec.defineList 仅原生支持 String/Boolean/Integer/Long/Double
    // 五种列表元素，不支持自定义对象作元素（night-config 序列化时会抛
    // WritingException: Unsupported value type），因此无法使用 [[stages]] 数组表嵌套对象。
    // 折中方案：每个阶段拆成 5 列平行列表，第 i 个阶段 = 各列表第 i 个元素组合。
    // 枚举列（modes / reductionModes）用字符串存储（TOML 中与枚举名一致），
    // 运行时经 Enum.valueOf 解析；非法值只跳过该阶段，绝不影响服务器启动。
    private static final ConfigValue<List<? extends String>> STAGE_HEIGHT_RANGES;
    private static final ConfigValue<List<? extends String>> STAGE_MODES;
    private static final ConfigValue<List<? extends Double>> STAGE_QTE_WINDOWS;
    private static final ConfigValue<List<? extends String>> STAGE_REDUCTION_MODES;
    private static final ConfigValue<List<? extends Double>> STAGE_REDUCTION_VALUES;

    /** 最终构建出的配置规范，供注册 Mod 配置时使用。 */
    public static final ModConfigSpec SPEC;

    static {
        BUILDER.comment(
                "SneakFall（潜行摔落减免）总体说明。",
                "按坠落高度区间定义减免规则：每个区间为一个阶段。",
                "每个阶段可独立选择触发模式（SUSTAIN / QTE）与减免计算方式（DISTANCE / PERCENT）。",
                "若坠落高度未命中任何阶段，则使用下方 general 分组中的保底（fallback）配置。");

        BUILDER.push("general");
        BUILDER.comment(
                "保底措施：坠落高度未落入任何 [stages] 阶段区间时使用（含区间空隙）。",
                "默认不减免（0.0），保证不意外免疫。");
        FALLBACK_MODE = BUILDER.defineEnum("fallbackMode", Mode.SUSTAIN);
        FALLBACK_QTE_WINDOW = BUILDER.defineInRange("fallbackQteWindow", 0.4, 0.0, 60.0);
        FALLBACK_REDUCTION_MODE = BUILDER.defineEnum("fallbackReductionMode", ReductionMode.DISTANCE);
        FALLBACK_REDUCTION_VALUE = BUILDER.defineInRange("fallbackReductionValue", 0.0, 0.0, 100.0);
        BUILDER.pop();

        BUILDER.push("stages");
        BUILDER.comment(
                "摔落减免阶段列表（可删改）。",
                "因底层配置库不支持嵌套对象数组（[[stages]] 数组表），改为 5 个等长平行列表：",
                "第 i 个阶段由 heightRanges[i]、modes[i]、qteWindows[i]、reductionModes[i]、reductionValues[i] 组合而成。",
                "示例（第 0 个阶段）：heightRanges=[\"3.0~20.0\"]、modes=[\"QTE\"]、qteWindows=[0.4]、",
                "reductionModes=[\"DISTANCE\"]、reductionValues=[1.5]，含义与原 [[stages]] 数组示例完全一致。",
                "heightRanges 格式：\"min~max\" 半开区间 [min,max)；\"min~\" 无上界；\"~max\" 无下界；\"all\" 全高度。",
                "modes 取值：SUSTAIN / QTE（大小写不敏感，非法值跳过该阶段）。",
                "qteWindows 仅 modes[i]=QTE 时生效，松开潜行立即归零重新计时。",
                "reductionModes 取值：DISTANCE / PERCENT（大小写不敏感，非法值跳过该阶段）。",
                "reductionValues：DISTANCE 模式为格数(0~100)；PERCENT 模式为比例(0~1，超 1 按 1 计)。",
                "五个列表长度不一致时按最短长度截断；某一行解析失败时仅跳过该阶段。");
        STAGE_HEIGHT_RANGES = BUILDER.defineList(
                "heightRanges",
                () -> defaultStages().heightRanges(),
                () -> "3.0~20.0",
                obj -> obj instanceof String);
        STAGE_MODES = BUILDER.defineList(
                "modes",
                () -> defaultStages().modes(),
                () -> Mode.QTE.name(),
                obj -> obj instanceof String);
        STAGE_QTE_WINDOWS = BUILDER.defineList(
                "qteWindows",
                () -> defaultStages().qteWindows(),
                () -> 0.4,
                obj -> obj instanceof Number);
        STAGE_REDUCTION_MODES = BUILDER.defineList(
                "reductionModes",
                () -> defaultStages().reductionModes(),
                () -> ReductionMode.DISTANCE.name(),
                obj -> obj instanceof String);
        STAGE_REDUCTION_VALUES = BUILDER.defineList(
                "reductionValues",
                () -> defaultStages().reductionValues(),
                () -> 1.5,
                obj -> obj instanceof Number);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    /**
     * 默认阶段的纯数据容器（仅承载默认值，不参与配置键定义，与旧的 StageSpec 不同）。
     * <p>第一次生成 config 时，各平行列表即以此单示例阶段为默认内容。</p>
     */
    private record StageDefaults(
            List<String> heightRanges,
            List<String> modes,
            List<Double> qteWindows,
            List<String> reductionModes,
            List<Double> reductionValues) {}

    /**
     * 默认阶段列表：内置一个示例阶段（heightRange="3.0~20.0"、mode=QTE、
     * qteWindow=0.4、reductionMode=DISTANCE、reductionValue=1.5），
     * 首次生成 config 时即包含此示例。
     */
    private static StageDefaults defaultStages() {
        return new StageDefaults(
                List.of("3.0~20.0"),
                List.of(Mode.QTE.name()),
                List.of(0.4),
                List.of(ReductionMode.DISTANCE.name()),
                List.of(1.5));
    }

    // ==================== 运行期数据结构 ====================

    /**
     * 运行期阶段：由平行列表（配置态）按 index 组装而来，供事件处理器直接使用。
     *
     * @param minHeight 阶段高度下界（含）
     * @param maxHeight 阶段高度上界（不含，半开区间）
     * @param mode 触发模式
     * @param qteWindowTicks QTE 窗口长度（游戏刻，1 秒 = 20 刻）
     * @param reductionMode 减免计算方式
     * @param reductionValue 减免数值（DISTANCE 为格数，PERCENT 为 0~1 比例）
     */
    public record RuntimeStage(
            double minHeight,
            double maxHeight,
            Mode mode,
            int qteWindowTicks,
            ReductionMode reductionMode,
            double reductionValue) {}

    /**
     * 高度区间（半开区间 [min, max)）。无界端用 ±无穷大（±Infinity）表示。
     */
    public record HeightRange(double min, double max) {}

    // ==================== 解析与匹配 ====================

    /**
     * 解析 heightRange 字符串。
     * <p>支持的格式：</p>
     * <ul>
     *   <li>"all" —— 全高度（等价 [−∞, +∞)）</li>
     *   <li>"a~b" —— 半开区间 [a, b)</li>
     *   <li>"a~" —— 下界为 a，无上界</li>
     *   <li>"~b" —— 无下界，上界为 b</li>
     * </ul>
     *
     * @param raw 原始字符串（可含首尾空白）
     * @return 解析成功返回 {@link HeightRange}；格式非法或数值非法返回 null
     */
    public static HeightRange parseHeightRange(String raw) {
        String s = raw.trim();
        // "all"（忽略大小写）：全高度
        if (s.equalsIgnoreCase("all")) {
            return new HeightRange(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
        // 必须包含 "~"
        if (!s.contains("~")) {
            return null;
        }
        // 按 "~" 切分（保留空段，如 "a~" 切出 ["a", ""]）
        String[] parts = s.split("~", -1);
        if (parts.length != 2) {
            return null;
        }

        // 两侧分别 trim；空串表示无界（对应 ±无穷）
        double min;
        double max;
        String minStr = parts[0].trim();
        String maxStr = parts[1].trim();
        try {
            min = minStr.isEmpty() ? Double.NEGATIVE_INFINITY : Double.parseDouble(minStr);
            max = maxStr.isEmpty() ? Double.POSITIVE_INFINITY : Double.parseDouble(maxStr);
        } catch (NumberFormatException e) {
            return null;
        }

        // 非法区间：min >= max 或任一为 NaN
        if (min >= max || Double.isNaN(min) || Double.isNaN(max)) {
            return null;
        }
        return new HeightRange(min, max);
    }

    /**
     * 构建保底阶段（覆盖全高度区间）。
     * 每次调用都从配置句柄现读现值——config 在事件触发时必已加载。
     */
    public static RuntimeStage buildFallback() {
        return new RuntimeStage(
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                FALLBACK_MODE.get(),
                (int) Math.ceil(FALLBACK_QTE_WINDOW.get() * 20.0),
                FALLBACK_REDUCTION_MODE.get(),
                normalize(FALLBACK_REDUCTION_MODE.get(), FALLBACK_REDUCTION_VALUE.get()));
    }

    /**
     * 根据坠落距离匹配阶段。
     * <p>按 index 同时遍历 5 个平行列表组装运行期阶段；任何一行解析失败仅跳过该 index。</p>
     *
     * @param fallDistance 实际坠落距离（格）
     * @return 命中的运行期阶段；未命中任何阶段时返回保底阶段
     */
    public static RuntimeStage matchStage(double fallDistance) {
        // 取回 5 个平行列表（按原始 List<?> 取，防御性避免 TOML 回读值类型与泛型不符）
        List<?> heightRanges = STAGE_HEIGHT_RANGES.get();
        List<?> modes = STAGE_MODES.get();
        List<?> qteWindows = STAGE_QTE_WINDOWS.get();
        List<?> reductionModes = STAGE_REDUCTION_MODES.get();
        List<?> reductionValues = STAGE_REDUCTION_VALUES.get();

        // 长度不一致时以最短为准
        int n = minLength(heightRanges, modes, qteWindows, reductionModes, reductionValues);
        if (heightRanges.size() != n || modes.size() != n || qteWindows.size() != n
                || reductionModes.size() != n || reductionValues.size() != n) {
            LOGGER.warn("[SneakFall] [stages] 五个平行列表长度不一致，按最短长度 {} 截断", n);
        }

        for (int i = 0; i < n; i++) {
            Object rawHeight = heightRanges.get(i);
            if (!(rawHeight instanceof String heightStr)) {
                LOGGER.warn("[SneakFall] 阶段 {} 的 heightRanges 值 '{}' 不是字符串，已跳过", i, rawHeight);
                continue;
            }
            HeightRange pr = parseHeightRange(heightStr);
            if (pr == null) {
                LOGGER.warn("[SneakFall] 阶段 {} 的 heightRanges '{}' 无法解析，已跳过", i, heightStr);
                continue;
            }
            if (!(fallDistance >= pr.min() && fallDistance < pr.max())) {
                continue; // 高度未落入本阶段区间
            }
            Mode mode = parseMode(modes.get(i), i);
            ReductionMode rmode = parseReductionMode(reductionModes.get(i), i);
            Double qw = parseDouble(qteWindows.get(i), i, "qteWindows");
            Double rv = parseDouble(reductionValues.get(i), i, "reductionValues");
            if (mode == null || rmode == null || qw == null || rv == null) {
                continue; // 该行任一列解析失败，已在各解析方法内告警并跳过
            }
            return new RuntimeStage(
                    pr.min(),
                    pr.max(),
                    mode,
                    (int) Math.ceil(qw * 20.0),
                    rmode,
                    normalize(rmode, rv));
        }
        return buildFallback();
    }

    /**
     * 将当前阶段配置打印到日志，便于排查配置问题。
     */
    public static void logStages() {
        List<?> heightRanges = STAGE_HEIGHT_RANGES.get();
        List<?> modes = STAGE_MODES.get();
        List<?> qteWindows = STAGE_QTE_WINDOWS.get();
        List<?> reductionModes = STAGE_REDUCTION_MODES.get();
        List<?> reductionValues = STAGE_REDUCTION_VALUES.get();

        int n = minLength(heightRanges, modes, qteWindows, reductionModes, reductionValues);
        if (heightRanges.size() != n || modes.size() != n || qteWindows.size() != n
                || reductionModes.size() != n || reductionValues.size() != n) {
            LOGGER.warn("[SneakFall] [stages] 五个平行列表长度不一致，按最短长度 {} 截断", n);
        }
        LOGGER.info("[SneakFall] 共加载 {} 个摔落减免阶段", n);
        for (int i = 0; i < n; i++) {
            Object rawHeight = heightRanges.get(i);
            String heightStr = rawHeight instanceof String s ? s : String.valueOf(rawHeight);
            HeightRange range = parseHeightRange(heightStr);
            if (range == null) {
                LOGGER.warn("[SneakFall] 阶段 {} 的 heightRanges '{}' 无法解析，已跳过该阶段", i, heightStr);
                continue;
            }
            LOGGER.info(
                    "[SneakFall] 阶段 {}：heightRanges='{}'（[{} ~ {})），modes={}，qteWindows={}，reductionModes={}，reductionValues={}",
                    i,
                    heightStr,
                    range.min(),
                    range.max(),
                    modes.get(i),
                    qteWindows.get(i),
                    reductionModes.get(i),
                    reductionValues.get(i));
        }
    }

    /**
     * 归一化减免数值：PERCENT 模式夹在 [0,1] 区间（超 1 按 1 计）；DISTANCE 模式原样返回。
     */
    private static double normalize(ReductionMode m, double v) {
        return m == ReductionMode.PERCENT ? Math.min(1.0, Math.max(0.0, v)) : v;
    }

    /**
     * 五个平行列表的最小长度。
     */
    private static int minLength(List<?> a, List<?> b, List<?> c, List<?> d, List<?> e) {
        return Math.min(a.size(), Math.min(b.size(), Math.min(c.size(), Math.min(d.size(), e.size()))));
    }

    /**
     * 解析触发模式字符串（大小写不敏感）；解析失败告警并返回 null（调用方跳过该阶段）。
     */
    private static Mode parseMode(Object raw, int index) {
        if (raw instanceof String s) {
            try {
                return Mode.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 落入下方告警
            }
        }
        LOGGER.warn("[SneakFall] 阶段 {} 的 modes 值 '{}' 不是合法枚举（SUSTAIN / QTE），已跳过", index, raw);
        return null;
    }

    /**
     * 解析减免计算方式字符串（大小写不敏感）；解析失败告警并返回 null（调用方跳过该阶段）。
     */
    private static ReductionMode parseReductionMode(Object raw, int index) {
        if (raw instanceof String s) {
            try {
                return ReductionMode.valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 落入下方告警
            }
        }
        LOGGER.warn("[SneakFall] 阶段 {} 的 reductionModes 值 '{}' 不是合法枚举（DISTANCE / PERCENT），已跳过", index, raw);
        return null;
    }

    /**
     * 解析数值列（TOML 回读可能是 Number 或 String）；解析失败告警并返回 null（调用方跳过该阶段）。
     */
    private static Double parseDouble(Object raw, int index, String column) {
        if (raw instanceof Number num) {
            return num.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // 落入下方告警
            }
        }
        LOGGER.warn("[SneakFall] 阶段 {} 的 {} 值 '{}' 不是合法数值，已跳过", index, column, raw);
        return null;
    }
}
