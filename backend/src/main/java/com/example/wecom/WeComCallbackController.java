package com.example.wecom;

import com.example.config.WeComProperties;
import com.example.task.service.TaskService;
import com.example.wecom.store.KvStoreService;
import com.qq.weixin.mp.aes.AesException;
import com.qq.weixin.mp.aes.WXBizJsonMsgCrypt;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企业微信「第三方应用」回调控制器。
 *
 * 数据回调URL: /wecom/callback/data    —— 用户消息、进入应用事件、通讯录变更
 * 指令回调URL: /wecom/callback/command —— suite_ticket、应用授权变更等指令
 *
 * 加解密 receiveid 规则（详见企业微信官方文档「加解密方案说明-附注：ReceiveId 含义」）：
 *   - 企业(ISV)第三方应用：URL验证(GET)以「服务商自身corpid」为主，suiteid 次之；
 *     POST 指令回调以 suite_id 解密；POST 数据回调以授权企业 corpid 解密
 *   - 个人主体创建的第三方应用：回调的 ReceiveId 为空字符串（本类已自动兜底兼容，无需额外配置）
 * 若仍报 -40005 corpid校验失败，日志会打印企业微信密文中实际携带的 receiveid，按提示修正
 * wecom.provider-corp-id / wecom.corp-id / wecom.suite-id 后重新保存回调配置即可。
 */
@RestController
@RequestMapping("/wecom/callback")
public class WeComCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WeComCallbackController.class);

    /** 企业微信 POST 回调体中的加密消息节点（XML 格式） */
    private static final Pattern XML_ENCRYPT =
            Pattern.compile("<Encrypt><!\\[CDATA\\[(.*?)\\]\\]></Encrypt>", Pattern.DOTALL);

    private final WeComProperties props;
    private final TaskService taskService;
    private final WeComApiClient weComApiClient;
    private final KvStoreService kvStore;

    public WeComCallbackController(WeComProperties props, TaskService taskService,
                                   WeComApiClient weComApiClient, KvStoreService kvStore) {
        this.props = props;
        this.taskService = taskService;
        this.weComApiClient = weComApiClient;
        this.kvStore = kvStore;
    }

    // ==================== 数据回调 ====================

    @GetMapping("/data")
    public String verifyData(@RequestParam String msg_signature,
                             @RequestParam String timestamp,
                             @RequestParam String nonce,
                             @RequestParam String echostr) {
        // URL验证(GET)企业微信用「服务商自身corpid」，其次才可能是授权方corpid/suiteid
        return verify(msg_signature, timestamp, nonce, echostr,
                props.getProviderCorpId(), props.getCorpId(), props.getSuiteId());
    }

    @PostMapping(value = "/data", produces = "application/json")
    public String dataCallback(@RequestParam String msg_signature,
                               @RequestParam String timestamp,
                               @RequestParam String nonce,
                               @RequestBody String body) {
        if (props.isCallbackReady()) {
            return "";
        }
        try {
            DecryptResult r = decryptBody(body, msg_signature, timestamp, nonce,
                    props.getCorpId(), props.getProviderCorpId(), props.getSuiteId());
            JSONObject json = new JSONObject(r.plain);
            String msgType = json.optString("MsgType");
            String fromUser = json.optString("FromUserName");
            String reply;
            if ("text".equals(msgType)) {
                reply = taskService.handleCallbackCommand(fromUser, json.optString("Content"));
            } else {
                reply = "仅支持文本消息，回复格式：#事项ID 状态";
            }
            // 回复加密必须使用与本次请求一致的 receiveid（个人主体第三方应用为空字符串）
            return newCrypt(r.receiveId).EncryptMsg(reply, timestamp, nonce);
        } catch (AesException e) {
            log.warn("处理数据回调失败: code={}, msg={}", e.getCode(), e.getMessage());
            return "";
        } catch (Exception e) {
            log.warn("处理数据回调异常", e);
            return "";
        }
    }

    // ==================== 指令回调 ====================

    @GetMapping("/command")
    public String verifyCommand(@RequestParam String msg_signature,
                                @RequestParam String timestamp,
                                @RequestParam String nonce,
                                @RequestParam String echostr) {
        // URL验证(GET)同样以「服务商自身corpid」为主，suiteid 次之
        return verify(msg_signature, timestamp, nonce, echostr,
                props.getProviderCorpId(), props.getSuiteId(), props.getCorpId());
    }

    @PostMapping("/command")
    public String commandCallback(@RequestParam String msg_signature,
                                  @RequestParam String timestamp,
                                  @RequestParam String nonce,
                                  @RequestBody String body) {
        try {
            DecryptResult r = decryptBody(body, msg_signature, timestamp, nonce,
                    props.getSuiteId(), props.getCorpId(), props.getProviderCorpId());
            String plain = r.plain;
            log.info("指令回调事件: {}", plain);
            String infoType = jsonOrXmlField(plain, "InfoType");
            // suite_ticket：企微每10分钟推送一次，是获取 suite_access_token 的必要凭证
            if ("suite_ticket".equals(infoType)) {
                String suiteTicket = jsonOrXmlField(plain, "SuiteTicket");
                if (!isBlank(suiteTicket)) {
                    weComApiClient.setSuiteTicket(suiteTicket);
                }
            } else if ("create_auth".equals(infoType)) {
                // 授权成功通知：AuthCode 为临时授权码（10分钟内有效，用于换取企业永久授权码）。
                // 先落库并立即响应，再异步换取永久授权码（文档要求响应须在1000ms内完成）
                String authCode = jsonOrXmlField(plain, "AuthCode");
                log.info("收到授权成功通知: SuiteId={}, AuthCode={}, TimeStamp={}, State={}, ExtraInfo={}",
                        jsonOrXmlField(plain, "SuiteId"),
                        authCode,
                        jsonOrXmlField(plain, "TimeStamp"),
                        jsonOrXmlField(plain, "State"),
                        jsonOrXmlField(plain, "ExtraInfo"));
                if (!isBlank(authCode)) {
                    kvStore.put(KvStoreService.KEY_AUTH_CODE, authCode);
                    weComApiClient.exchangePermanentCode(authCode);
                }
            }
            return "success";
        } catch (AesException e) {
            log.warn("处理指令回调失败: code={}, msg={}", e.getCode(), e.getMessage());
            return "success";
        } catch (Exception e) {
            log.warn("处理指令回调异常", e);
            return "success";
        }
    }

    // ==================== 公共逻辑 ====================

    /**
     * GET 校验 URL 有效性。第三方应用回调的 receiveid 视回调类型而定，
     * 依次尝试传入的候选 receiveid，命中即返回明文；全部失败时打印企业微信实际使用的 receiveid 便于排查。
     */
    private String verify(String msgSignature, String timestamp, String nonce, String echostr,
                          String... candidates) {
        if (props.isCallbackReady()) {
            return "callback not configured";
        }
        List<String> ids = new ArrayList<>();
        for (String c : candidates) {
            if (!isBlank(c) && !ids.contains(c)) {
                ids.add(c);
            }
        }
        // 个人主体第三方应用的回调：企微加密时 ReceiveId 为空字符串（官方「附注：ReceiveId 含义」），兜底尝试
        if (!ids.contains("")) {
            ids.add("");
        }
        AesException last = null;
        for (String receiveId : ids) {
            try {
                String echo = newCrypt(receiveId).VerifyURL(msgSignature, timestamp, nonce, echostr);
                log.info("验证回调URL成功: 该回调使用的 receiveid=[{}]{}", receiveId,
                        receiveId.isEmpty() ? "（空字符串，个人主体第三方应用）" : "");
                return echo;
            } catch (AesException e) {
                last = e;
                log.debug("验证回调URL尝试 receiveid={} 失败: code={}, msg={}", receiveId, e.getCode(), e.getMessage());
            }
        }
        String actual = extractActualReceiveId(echostr);
        if (actual != null) {
            log.warn("验证回调URL失败: code={}, msg={}。企业微信密文中实际携带的 receiveid=[{}]。{}",
                    last == null ? -1 : last.getCode(),
                    last == null ? "unknown" : last.getMessage(),
                    actual,
                    actual.isEmpty()
                            ? "这是个人主体第三方应用的特征，代码已自动兼容空字符串 ReceiveId，无需修改配置"
                            : "请将 wecom.provider-corp-id / wecom.corp-id / wecom.suite-id 改为该值后重新保存回调配置");
        } else {
            log.warn("验证回调URL失败: code={}, msg={}",
                    last == null ? -1 : last.getCode(),
                    last == null ? "unknown" : last.getMessage());
        }
        return "verify failed";
    }

    /**
     * 从 POST 回调体中提取加密串：兼容 XML（&lt;Encrypt&gt; 节点）与 JSON（encrypt 字段）两种格式。
     */
    private String extractEncrypt(String body) throws Exception {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("回调体为空");
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            try {
                return new JSONObject(trimmed).getString("encrypt");
            } catch (Exception e) {
                throw new IllegalArgumentException("JSON回调体缺少 encrypt 字段");
            }
        }
        Matcher m = XML_ENCRYPT.matcher(trimmed);
        if (m.find()) {
            return m.group(1);
        }
        throw new IllegalArgumentException("回调体缺少 <Encrypt> 节点");
    }

    /** 解密 echostr，返回企业微信加密时实际使用的 receiveid（失败返回 null） */
    private String extractActualReceiveId(String echostr) {
        try {
            return newCrypt(props.getCorpId()).extractReceiveId(echostr);
        } catch (AesException e) {
            return null;
        }
    }

    /**
     * 从解密后的指令回调明文中取值：兼容 JSON（如 {"InfoType":"suite_ticket","SuiteTicket":"..."}）
     * 与 XML（如 &lt;InfoType&gt;&lt;![CDATA[suite_ticket]]&gt;&lt;/InfoType&gt;）两种格式。
     */
    private String jsonOrXmlField(String plain, String field) {
        if (plain == null) {
            return null;
        }
        String trimmed = plain.trim();
        if (trimmed.startsWith("{")) {
            try {
                String v = new JSONObject(trimmed).optString(field, null);
                return "null".equals(v) ? null : v;
            } catch (Exception e) {
                return null;
            }
        }
        Matcher cdata = Pattern.compile("<" + field + "><!\\[CDATA\\[(.*?)\\]\\]></" + field + ">", Pattern.DOTALL)
                .matcher(trimmed);
        if (cdata.find()) {
            return cdata.group(1);
        }
        Matcher plainTag = Pattern.compile("<" + field + ">(.*?)</" + field + ">", Pattern.DOTALL)
                .matcher(trimmed);
        return plainTag.find() ? plainTag.group(1).trim() : null;
    }

    /**
     * 依次用候选 receiveid 解密回调体，返回解密明文及实际命中的 receiveid。
     * 最后固定追加空字符串候选：个人主体创建的第三方应用回调，企微加密时 ReceiveId 为空字符串
     * （官方「附注：ReceiveId 含义」）；企业(ISV)第三方应用则使用 corpid / suiteid。
     */
    private DecryptResult decryptBody(String body, String msgSignature, String timestamp, String nonce,
                                      String... candidates) throws Exception {
        List<String> ids = new ArrayList<>();
        for (String c : candidates) {
            if (!isBlank(c) && !ids.contains(c)) {
                ids.add(c);
            }
        }
        if (!ids.contains("")) {
            ids.add("");
        }
        AesException last = null;
        for (String receiveId : ids) {
            try {
                String plain = newCrypt(receiveId)
                        .DecryptEncrypt(extractEncrypt(body), msgSignature, timestamp, nonce);
                return new DecryptResult(receiveId, plain);
            } catch (AesException e) {
                last = e;
                log.debug("解密回调尝试 receiveid={} 失败: code={}, msg={}", receiveId, e.getCode(), e.getMessage());
            }
        }
        throw last;
    }

    /** 解密结果：命中的 receiveid（回复加密时必须保持一致）与解密明文 */
    private static class DecryptResult {
        final String receiveId;
        final String plain;

        DecryptResult(String receiveId, String plain) {
            this.receiveId = receiveId;
            this.plain = plain;
        }
    }

    private WXBizJsonMsgCrypt newCrypt(String receiveId) throws AesException {
        return new WXBizJsonMsgCrypt(props.getToken(), props.getAesKey(), receiveId);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
