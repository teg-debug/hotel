package com.hotel.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 客服意图识别：30 组话术的参数化用例。
 *
 * <p>被测对象是 {@link IntentClassifier}——纯规则实现（出范围闸门 + 关键词匹配 + 兜底一般咨询），
 * 不依赖大模型与网络，因此可以离线确定性验证。判定标准与 {@code ChatServiceImpl} 的分支一一对应：</p>
 *
 * <ol>
 *   <li><b>转人工</b>：出范围意图（退款/投诉/隐私/法律/医疗/报警等）或用户明确要求人工；</li>
 *   <li><b>知识库检索</b>：酒店信息 / 服务设施 / 周边推荐三类意图；</li>
 *   <li><b>模型生成</b>：其余意图（预订、查房态、查订单、服务请求、闲聊、一般咨询）。</li>
 * </ol>
 *
 * <p>30 组话术按四个业务场景沉淀（政策咨询 11 组、在线预订 6 组、订单查询 5 组、投诉转接 8 组），
 * 投诉转接组内含隐私、治安、法律三类敏感话题——这些必须转人工而不是由机器人作答。
 * 表里的 {@code expectedIntent} 是<b>需求口径</b>的期望值，不是照着实现反推的。</p>
 *
 * <p>本轮修复的 4 处缺口（首轮运行实测暴露，见各条 note）：词典新增「几点可以入住」等入住/退房
 * 时间变体与「订单」「宠物」类词，出范围词表补上「泄露」「个人信息」以覆盖语序倒装；
 * 同时把匹配顺序从「取决于 {@code Map} 迭代顺序」改为确定性规则（长词优先 → 先出现者优先 → 词字典序），
 * 否则新增等长词会引入同一句话在不同 JVM 上结论不同的隐患。</p>
 *
 * <p>本用例只覆盖「规则路由」这一段：知识库检索答得对不对、模型调工具的参数准不准，
 * 需要真实知识库与模型，不在本用例范围内（见类末说明）。</p>
 */
@DisplayName("AI 客服意图识别：30 组话术")
class ChatIntentRecognitionTest {

    private final IntentClassifier classifier = new IntentClassifier();

    /** 话术所属业务场景（对应「政策咨询 / 在线预订 / 订单查询 / 投诉转接」四类） */
    enum Scenario {
        POLICY("政策咨询"),
        BOOKING("在线预订"),
        ORDER("订单查询"),
        COMPLAINT("投诉转接");

        private final String label;

        Scenario(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /** 路由结论：与 ChatServiceImpl 的分支一一对应，是本用例的判定标准 */
    enum Route {
        HUMAN("转人工"),
        RAG("知识库检索"),
        MODEL("模型生成");

        private final String label;

        Route(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    /**
     * 一组话术。
     *
     * @param expectedIntent 需求口径的期望意图
     * @param sensitive      是否属于敏感话题（必须转人工）
     * @param note           该话术考察的点，或本轮修复记录
     */
    record Utterance(Scenario scenario, String text, String expectedIntent, boolean sensitive, String note) {
    }

    // ===================== 30 组话术 =====================

    private static final List<Utterance> UTTERANCES = List.of(
            // ---- 政策咨询（知识库类意图 → 检索）----
            new Utterance(Scenario.POLICY, "你们酒店几点可以入住？",
                    IntentClassifier.INTENT_HOTEL_INFO, false, "入住时间变体：首轮漏判，词典已补「几点可以入住」等写法"),
            new Utterance(Scenario.POLICY, "退房时间是几点？",
                    IntentClassifier.INTENT_HOTEL_INFO, false, "退房时间"),
            new Utterance(Scenario.POLICY, "酒店地址在哪，怎么走？",
                    IntentClassifier.INTENT_HOTEL_INFO, false, "地址与路线"),
            new Utterance(Scenario.POLICY, "房间里有 wifi 吗？",
                    IntentClassifier.INTENT_FACILITY, false, "长词优先：wifi 应压过房间"),
            new Utterance(Scenario.POLICY, "有停车场吗？",
                    IntentClassifier.INTENT_FACILITY, false, "停车"),
            new Utterance(Scenario.POLICY, "早餐几点开始？",
                    IntentClassifier.INTENT_FACILITY, false, "早餐"),
            new Utterance(Scenario.POLICY, "可以开发票吗？",
                    IntentClassifier.INTENT_FACILITY, false, "发票"),
            new Utterance(Scenario.POLICY, "有游泳池或健身房吗？",
                    IntentClassifier.INTENT_FACILITY, false, "康体设施"),
            new Utterance(Scenario.POLICY, "酒店附近有地铁站吗？",
                    IntentClassifier.INTENT_NEARBY, false, "周边交通"),
            new Utterance(Scenario.POLICY, "从机场怎么到酒店？",
                    IntentClassifier.INTENT_NEARBY, false, "机场接送"),
            new Utterance(Scenario.POLICY, "我能带宠物入住吗？",
                    IntentClassifier.INTENT_FACILITY, false, "宠物政策：首轮漏判，词典已补「带宠物」「宠物」等词"),

            // ---- 在线预订 ----
            new Utterance(Scenario.BOOKING, "我想订一间大床房",
                    IntentClassifier.INTENT_BOOKING, false, "长词优先：订一间"),
            new Utterance(Scenario.BOOKING, "帮我订明天晚上的房间",
                    IntentClassifier.INTENT_BOOKING, false, "帮我订 应压过 房间"),
            new Utterance(Scenario.BOOKING, "现在还能预订吗？",
                    IntentClassifier.INTENT_BOOKING, false, "预订"),
            new Utterance(Scenario.BOOKING, "我要下单，怎么操作？",
                    IntentClassifier.INTENT_BOOKING, false, "下单"),
            new Utterance(Scenario.BOOKING, "有空房吗？我想住两晚",
                    IntentClassifier.INTENT_QUERY_ROOM, false, "房态咨询"),
            new Utterance(Scenario.BOOKING, "大床房多少钱一晚？",
                    IntentClassifier.INTENT_QUERY_ROOM, false, "价格咨询"),

            // ---- 订单查询 ----
            new Utterance(Scenario.ORDER, "我的订单到哪了？",
                    IntentClassifier.INTENT_QUERY_ORDER, false, "两个等长词同属查订单"),
            new Utterance(Scenario.ORDER, "帮我查一下订单状态",
                    IntentClassifier.INTENT_QUERY_ORDER, false, "订单状态"),
            new Utterance(Scenario.ORDER, "订单号是 123456，帮我看看",
                    IntentClassifier.INTENT_QUERY_ORDER, false, "订单号"),
            new Utterance(Scenario.ORDER, "我要看我的订单列表",
                    IntentClassifier.INTENT_QUERY_ORDER, false, "订单列表"),
            new Utterance(Scenario.ORDER, "订单能取消吗？",
                    IntentClassifier.INTENT_QUERY_ORDER, false, "首轮被「订」字误判为预订，词典已补「订单」一词"),

            // ---- 投诉转接（含敏感话题，一律转人工）----
            new Utterance(Scenario.COMPLAINT, "我要投诉你们酒店卫生太差",
                    IntentClassifier.INTENT_OUT_OF_SCOPE, false, "投诉"),
            new Utterance(Scenario.COMPLAINT, "房间隔音太差，我要求退款",
                    IntentClassifier.INTENT_OUT_OF_SCOPE, false, "退款"),
            new Utterance(Scenario.COMPLAINT, "房间有蟑螂，你们必须赔偿",
                    IntentClassifier.INTENT_OUT_OF_SCOPE, false, "索赔"),
            new Utterance(Scenario.COMPLAINT, "我要转人工",
                    IntentClassifier.INTENT_OUT_OF_SCOPE, false, "明确要求人工"),
            new Utterance(Scenario.COMPLAINT, "你们客服回复太慢了",
                    IntentClassifier.INTENT_OUT_OF_SCOPE, false, "对客服不满也应转人工"),
            new Utterance(Scenario.COMPLAINT, "我要找律师起诉你们",
                    IntentClassifier.INTENT_OUT_OF_SCOPE, true, "法律话题"),
            new Utterance(Scenario.COMPLAINT, "隔壁房间有人打架，我要报警",
                    IntentClassifier.INTENT_OUT_OF_SCOPE, true, "治安话题"),
            new Utterance(Scenario.COMPLAINT, "你们是不是泄露了我的个人信息",
                    IntentClassifier.INTENT_OUT_OF_SCOPE, true, "隐私话题：首轮因语序倒装漏判（词表只有「个人信息泄露」），已补「泄露」「个人信息」")
    );

    /** 期望意图 → 路由：出范围走转人工，知识类意图走检索，其余走模型 */
    private static Route routeOfIntent(String intent) {
        if (IntentClassifier.INTENT_OUT_OF_SCOPE.equals(intent)) {
            return Route.HUMAN;
        }
        return IntentClassifier.isKnowledgeIntent(intent) ? Route.RAG : Route.MODEL;
    }

    /** 按生产分支算出实际路由：先看 outOfScope，再看是否知识类意图 */
    private static Route actualRoute(IntentClassifier.IntentResult result) {
        if (result.outOfScope()) {
            return Route.HUMAN;
        }
        return IntentClassifier.isKnowledgeIntent(result.intent()) ? Route.RAG : Route.MODEL;
    }

    // ===================== 主用例 =====================

    static Stream<Arguments> utterances() {
        return UTTERANCES.stream()
                .map(utterance -> Arguments.of(utterance.scenario(), utterance.text(),
                        utterance.expectedIntent(), routeOfIntent(utterance.expectedIntent()),
                        utterance.sensitive(), utterance.note()));
    }

    @ParameterizedTest(name = "[{index}] {1}")
    @MethodSource("utterances")
    @DisplayName("30 组话术：意图与路由都应与期望一致")
    void routesCorrectly(Scenario scenario, String text, String expectedIntent, Route expectedRoute,
                         boolean sensitive, String note) {
        IntentClassifier.IntentResult result = classifier.classify(text);

        assertThat(result.intent())
                .as("[%s] 「%s」应识别为 %s（%s）", scenario.label(), text, expectedIntent, note)
                .isEqualTo(expectedIntent);
        assertThat(actualRoute(result))
                .as("[%s] 「%s」应路由到 %s", scenario.label(), text, expectedRoute.label())
                .isEqualTo(expectedRoute);
        assertThat(result.outOfScope())
                .as("出范围标记应与期望意图一致（期望 %s）", expectedIntent)
                .isEqualTo(IntentClassifier.INTENT_OUT_OF_SCOPE.equals(expectedIntent));
        if (sensitive) {
            assertThat(result.reason())
                    .as("敏感话题「%s」转人工时必须带上可展示的原因", text)
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("30 组话术的路由与标签都应全部正确，并满足不低于 90% 的简历口径")
    void accuracyMeetsClaim() throws Exception {
        Map<Scenario, Long> byScenario = UTTERANCES.stream()
                .collect(Collectors.groupingBy(Utterance::scenario, Collectors.counting()));
        long labelMisses = UTTERANCES.stream()
                .filter(utterance -> !classifier.classify(utterance.text()).intent()
                        .equals(utterance.expectedIntent()))
                .count();
        long routeMisses = UTTERANCES.stream()
                .filter(utterance -> !actualRoute(classifier.classify(utterance.text()))
                        .equals(routeOfIntent(utterance.expectedIntent())))
                .count();

        long labelHits = UTTERANCES.size() - labelMisses;
        long routeHits = UTTERANCES.size() - routeMisses;
        double labelAccuracy = 100.0 * labelHits / UTTERANCES.size();
        double routeAccuracy = 100.0 * routeHits / UTTERANCES.size();

        String summary = String.format("""
                ===== AI 客服意图识别：30 组话术 =====
                场景分布 : %s
                意图标签 : %d/%d = %.1f%%
                路由结论 : %d/%d = %.1f%%（转人工 %d 组 / 知识检索 %d 组 / 模型生成 %d 组）
                简历口径 : 路由正确率 90%% 以上 → 当前余量 %d 组
                ====================================
                """,
                byScenario.entrySet().stream()
                        .map(entry -> entry.getKey().label() + " " + entry.getValue() + " 组")
                        .collect(Collectors.joining("、")),
                labelHits, UTTERANCES.size(), labelAccuracy,
                routeHits, UTTERANCES.size(), routeAccuracy,
                UTTERANCES.stream().filter(u -> routeOfIntent(u.expectedIntent()) == Route.HUMAN).count(),
                UTTERANCES.stream().filter(u -> routeOfIntent(u.expectedIntent()) == Route.RAG).count(),
                UTTERANCES.stream().filter(u -> routeOfIntent(u.expectedIntent()) == Route.MODEL).count(),
                routeHits - (int) Math.ceil(UTTERANCES.size() * 0.9));
        System.out.println(summary);
        write(summary);

        assertThat(UTTERANCES).as("话术总数应为 30 组").hasSize(30);
        assertThat(routeAccuracy)
                .as("路由正确率应不低于简历口径的 90%")
                .isGreaterThanOrEqualTo(90.0);
        // 词典补齐后这里是回归红线：任何一条新偏差都应先修规则，而不是放宽断言
        assertThat(routeMisses).as("路由偏差数应为 0").isZero();
        assertThat(labelMisses).as("意图标签偏差数应为 0").isZero();
    }

    private void write(String content) throws Exception {
        Path path = Path.of("target", "benchmark", "intent-recognition-summary.txt");
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("30 组话术应覆盖四个业务场景，且包含敏感话题")
    void coversClaimedScenarios() {
        assertThat(UTTERANCES).extracting(Utterance::scenario)
                .as("四类场景都应有用例")
                .contains(Scenario.values());
        assertThat(UTTERANCES).hasSize(30);
        assertThat(UTTERANCES).filteredOn(utterance -> utterance.scenario() == Scenario.POLICY)
                .as("政策咨询应有成组用例").hasSizeGreaterThanOrEqualTo(10);
        assertThat(UTTERANCES).filteredOn(utterance -> utterance.scenario() == Scenario.BOOKING)
                .as("在线预订应有成组用例").hasSizeGreaterThanOrEqualTo(5);
        assertThat(UTTERANCES).filteredOn(utterance -> utterance.scenario() == Scenario.ORDER)
                .as("订单查询应有成组用例").hasSizeGreaterThanOrEqualTo(5);
        assertThat(UTTERANCES).filteredOn(utterance -> utterance.scenario() == Scenario.COMPLAINT)
                .as("投诉转接应有成组用例").hasSizeGreaterThanOrEqualTo(5);
        assertThat(UTTERANCES).filteredOn(Utterance::sensitive)
                .as("敏感话题（隐私/治安/法律）必须有用例")
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(UTTERANCES).filteredOn(Utterance::sensitive)
                .as("敏感话题的期望路由都必须是转人工")
                .allSatisfy(utterance -> assertThat(routeOfIntent(utterance.expectedIntent()))
                        .isEqualTo(Route.HUMAN));
    }

    // ===================== 规则本身的边界用例 =====================

    @Test
    @DisplayName("出范围闸门优先于词典命中")
    void outOfScopeTakesPrecedenceOverDictionary() {
        IntentClassifier.IntentResult result = classifier.classify("有停车场吗？另外我要投诉你们");
        assertThat(result.intent()).isEqualTo(IntentClassifier.INTENT_OUT_OF_SCOPE);
        assertThat(result.outOfScope()).isTrue();
        assertThat(result.reason()).as("转人工要带上可展示的原因").isNotBlank();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("transferRequests")
    @DisplayName("明确要求人工的各种说法都应转人工")
    void explicitTransferRequestsGoToHuman(String text) {
        IntentClassifier.IntentResult result = classifier.classify(text);
        assertThat(result.outOfScope()).as("「%s」应转人工", text).isTrue();
        assertThat(result.reason()).contains("转接人工");
    }

    static Stream<Arguments> transferRequests() {
        return Stream.of("转人工", "我要真人客服", "帮我转接人工", "能不能找个人工客服")
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("blankInputs")
    @DisplayName("空输入兜底为一般咨询，不应误判转人工")
    void blankInputFallsBackToGeneral(String text) {
        IntentClassifier.IntentResult result = classifier.classify(text);
        assertThat(result.intent()).isEqualTo(IntentClassifier.INTENT_GENERAL);
        assertThat(result.outOfScope()).as("空输入不应触发转人工").isFalse();
    }

    static Stream<Arguments> blankInputs() {
        return Stream.of("", "   ", "\n").map(Arguments::of);
    }

    @Test
    @DisplayName("等长跨意图关键词按「先出现者」归类，结论与 JVM 无关")
    void equalLengthKeywordsPreferTheEarlierOne() {
        // 「附近」(周边推荐) 与「餐厅」(服务设施) 等长且都能命中。
        // 旧实现按 Map 迭代顺序决定归属，同一句话在不同 JVM 上可能给出不同意图；
        // 现在按「先出现者优先」固定为周边推荐，这里把它锁成断言。
        assertThat(classifier.classify("附近有餐厅吗").intent())
                .isEqualTo(IntentClassifier.INTENT_NEARBY);
        assertThat(classifier.classify("附近有餐厅推荐吗").intent())
                .as("长词优先仍应压过「先出现者」").isEqualTo(IntentClassifier.INTENT_NEARBY);
        assertThat(classifier.classify("餐厅在附近吗").intent())
                .as("换序后由先出现的「餐厅」胜出").isEqualTo(IntentClassifier.INTENT_FACILITY);
    }

    @ParameterizedTest(name = "[{index}] {0} → 知识类={1}")
    @MethodSource("knowledgeIntents")
    @DisplayName("知识库类意图的判定范围")
    void knowledgeIntentScope(String intent, boolean expected) {
        assertThat(IntentClassifier.isKnowledgeIntent(intent)).isEqualTo(expected);
    }

    static Stream<Arguments> knowledgeIntents() {
        return Stream.of(
                Arguments.of(IntentClassifier.INTENT_HOTEL_INFO, true),
                Arguments.of(IntentClassifier.INTENT_FACILITY, true),
                Arguments.of(IntentClassifier.INTENT_NEARBY, true),
                Arguments.of(IntentClassifier.INTENT_BOOKING, false),
                Arguments.of(IntentClassifier.INTENT_QUERY_ROOM, false),
                Arguments.of(IntentClassifier.INTENT_QUERY_ORDER, false),
                Arguments.of(IntentClassifier.INTENT_SERVICE, false),
                Arguments.of(IntentClassifier.INTENT_CHITCHAT, false),
                Arguments.of(IntentClassifier.INTENT_GENERAL, false),
                Arguments.of(IntentClassifier.INTENT_OUT_OF_SCOPE, false));
    }

    @Test
    @DisplayName("词典里的意图常量应全部被本用例覆盖或有明确归属")
    void allIntentsAreAccountedFor() {
        Set<String> covered = UTTERANCES.stream()
                .flatMap(utterance -> Stream.of(utterance.expectedIntent()))
                .collect(Collectors.toSet());
        Set<String> allIntents = Set.of(IntentClassifier.INTENT_BOOKING, IntentClassifier.INTENT_QUERY_ROOM,
                IntentClassifier.INTENT_QUERY_ORDER, IntentClassifier.INTENT_SERVICE,
                IntentClassifier.INTENT_HOTEL_INFO, IntentClassifier.INTENT_FACILITY,
                IntentClassifier.INTENT_NEARBY, IntentClassifier.INTENT_CHITCHAT,
                IntentClassifier.INTENT_GENERAL, IntentClassifier.INTENT_OUT_OF_SCOPE);

        assertThat(allIntents).as("意图常量集合应与实现一致（新增意图时本用例应提醒补充话术）")
                .hasSize(10);
        assertThat(covered).as("预订/查房态/查订单/酒店信息/服务设施/周边/出范围 应由话术覆盖")
                .contains(IntentClassifier.INTENT_BOOKING, IntentClassifier.INTENT_QUERY_ROOM,
                        IntentClassifier.INTENT_QUERY_ORDER, IntentClassifier.INTENT_HOTEL_INFO,
                        IntentClassifier.INTENT_FACILITY, IntentClassifier.INTENT_NEARBY,
                        IntentClassifier.INTENT_OUT_OF_SCOPE);
        // 闲聊、服务请求、一般咨询暂未纳入这 30 组（业务量小），此处显式列出以免被误认为遗漏
        assertThat(allIntents).containsAll(Arrays.asList(IntentClassifier.INTENT_CHITCHAT,
                IntentClassifier.INTENT_SERVICE, IntentClassifier.INTENT_GENERAL));
    }
}
