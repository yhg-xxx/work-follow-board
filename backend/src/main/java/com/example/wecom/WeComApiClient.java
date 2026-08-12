package com.example.wecom;

import com.example.config.WeComProperties;
import com.example.task.entity.NotifyLog;
import com.example.task.entity.Task;
import com.example.task.repository.NotifyLogRepository;
import com.example.wecom.store.KvStoreService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 企业微信 API 客户端：access_token 管理 + 应用消息推送。
 */
@Component
public class WeComApiClient {

    private static final Logger log = LoggerFactory.getLogger(WeComApiClient.class);
    private static final String BASE = "https://qyapi.weixin.qq.com";

    private final WeComProperties props;
    private final NotifyLogRepository notifyLogRepository;
    private final KvStoreService kvStore;
    private final RestClient restClient = RestClient.create();

    private volatile String accessToken;
    private volatile long tokenExpireAt;
    private final ReentrantLock tokenLock = new ReentrantLock();

    /** 企微每10分钟推送到指令回调URL的 suite_ticket（获取 suite_access_token 的凭证） */
    private volatile String suiteTicket;

    private volatile String suiteAccessToken;
    private volatile long suiteTokenExpireAt;
    private final ReentrantLock suiteTokenLock = new ReentrantLock();

    public WeComApiClient(WeComProperties props, NotifyLogRepository notifyLogRepository, KvStoreService kvStore) {
        this.props = props;
        this.notifyLogRepository = notifyLogRepository;
        this.kvStore = kvStore;
        // 重启后从库恢复最新 suite_ticket，避免应用重启后无法获取 suite_access_token
        String savedTicket = kvStore.get(KvStoreService.KEY_SUITE_TICKET);
        if (savedTicket != null && !savedTicket.isBlank()) {
            this.suiteTicket = savedTicket;
            log.info("已从数据库恢复 suite_ticket");
        }
    }

    /** 推送结果 */
    public record SendResult(int errcode, String errmsg) {
        public boolean ok() {
            return errcode == 0;
        }
    }

    /**
     * 获取 access_token（内存缓存，有效期 7200s，剩余 300s 时刷新，加锁单例）。
     */
    public String getAccessToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpireAt - 300_000) {
            return accessToken;
        }
        tokenLock.lock();
        try {
            now = System.currentTimeMillis();
            if (accessToken != null && now < tokenExpireAt - 300_000) {
                return accessToken;
            }
            String url = BASE + "/cgi-bin/gettoken?corpid=" + props.getCorpId()
                    + "&corpsecret=" + props.getSecret();
            JsonNode json = restClient.get().uri(url).retrieve().body(JsonNode.class);
            int errcode = json.path("errcode").asInt(-1);
            if (errcode == 0) {
                accessToken = json.path("access_token").asText();
                tokenExpireAt = now + json.path("expires_in").asLong(7200) * 1000;
                return accessToken;
            }
            log.error("获取 access_token 失败: errcode={}, errmsg={}",
                    errcode, json.path("errmsg").asText());
            return null;
        } catch (Exception e) {
            log.error("获取 access_token 异常", e);
            return null;
        } finally {
            tokenLock.unlock();
        }
    }

    /**
     * 记录企业微信指令回调推送的最新 suite_ticket（每10分钟一次，实时变更）。
     * 获取 suite_access_token 时必须使用最新接收到的 ticket。
     */
    public void setSuiteTicket(String ticket) {
        this.suiteTicket = ticket;
        kvStore.put(KvStoreService.KEY_SUITE_TICKET, ticket);
        log.info("最新suite_ticket={}", ticket);
    }

    /** 当前缓存的 suite_ticket，尚未收到时为 null */
    public String getSuiteTicket() {
        return suiteTicket;
    }

    /**
     * 获取第三方应用凭证 suite_access_token（内存缓存2小时，剩余300s时刷新，加锁单例）。
     * 依赖企微推送到指令回调URL的 suite_ticket；若尚未收到会返回 null 并告警。
     */
    public String getSuiteAccessToken() {
        long now = System.currentTimeMillis();
        if (suiteAccessToken != null && now < suiteTokenExpireAt - 300_000) {
            return suiteAccessToken;
        }
        suiteTokenLock.lock();
        try {
            now = System.currentTimeMillis();
            if (suiteAccessToken != null && now < suiteTokenExpireAt - 300_000) {
                return suiteAccessToken;
            }
            if (suiteTicket == null || suiteTicket.isBlank()) {
                log.warn("尚未收到 suite_ticket（企微每10分钟推送到指令回调URL），无法获取 suite_access_token");
                return null;
            }
            String url = BASE + "/cgi-bin/service/get_suite_token";
            Map<String, Object> body = Map.of(
                    "suite_id", props.getSuiteId(),
                    "suite_secret", props.getSuiteSecret(),
                    "suite_ticket", suiteTicket);
            JsonNode json = restClient.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            // 该接口仅调用失败时才返回 errcode；成功时无 errcode 字段，path().asInt(0) 视为成功
            int errcode = json.path("errcode").asInt(0);
            if (errcode == 0) {
                suiteAccessToken = json.path("suite_access_token").asText();
                suiteTokenExpireAt = now + json.path("expires_in").asLong(7200) * 1000;
                kvStore.put(KvStoreService.KEY_SUITE_ACCESS_TOKEN, suiteAccessToken);
                log.info("获取 suite_access_token 成功，有效期 {} 秒", (suiteTokenExpireAt - now) / 1000);
                return suiteAccessToken;
            }
            log.error("获取 suite_access_token 失败: errcode={}, errmsg={}",
                    errcode, json.path("errmsg").asText());
            return null;
        } catch (Exception e) {
            log.error("获取 suite_access_token 异常", e);
            return null;
        } finally {
            suiteTokenLock.unlock();
        }
    }

    /**
     * 用企业临时授权码换取企业永久授权码并落库。
     * 由授权成功通知（create_auth）触发，异步执行，避免拖慢回调响应。
     */
    @Async
    public void exchangePermanentCode(String authCode) {
        String suiteToken = getSuiteAccessToken();
        if (suiteToken == null) {
            log.warn("换取企业永久授权码失败: 获取 suite_access_token 失败");
            return;
        }
        try {
            String url = BASE + "/cgi-bin/service/v2/get_permanent_code?suite_access_token=" + suiteToken;
            Map<String, Object> body = Map.of("auth_code", authCode);
            JsonNode json = restClient.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            int errcode = json.path("errcode").asInt(0);
            if (errcode == 0) {
                String permanentCode = json.path("permanent_code").asText("");
                String corpid = json.path("auth_corp_info").path("corpid").asText("");
                if (!permanentCode.isBlank()) {
                    kvStore.put(KvStoreService.KEY_PERMANENT_CODE, permanentCode);
                }
                if (!corpid.isBlank()) {
                    kvStore.put(KvStoreService.KEY_AUTHORIZED_CORP_ID, corpid);
                }
                log.info("换取企业永久授权码成功: corpid={}，已落库 permanent_code/authorized_corpid", corpid);
            } else {
                log.error("换取企业永久授权码失败: errcode={}, errmsg={}",
                        errcode, json.path("errmsg").asText());
            }
        } catch (Exception e) {
            log.error("换取企业永久授权码异常", e);
        }
    }

    /**
     * 发送文本应用消息。
     *
     * @param touser  接收人企微 userid
     * @param content 文本内容
     */
    public SendResult sendTextMessage(String touser, String content) {
        String token = getAccessToken();
        if (token == null) {
            return new SendResult(-1, "获取access_token失败");
        }
        String url = BASE + "/cgi-bin/message/send?access_token=" + token;
        try {
            Map<String, Object> body = Map.of(
                    "touser", touser,
                    "msgtype", "text",
                    "agentid", Integer.parseInt(props.getAgentId()),
                    "text", Map.of("content", content));
            JsonNode json = restClient.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(JsonNode.class);
            return new SendResult(json.path("errcode").asInt(-1), json.path("errmsg").asText());
        } catch (Exception e) {
            log.error("发送企微消息异常 touser={}", touser, e);
            return new SendResult(-2, e.getMessage());
        }
    }

    /**
     * 发送事项通知并记录推送日志、回写通知状态。
     */
    public SendResult sendTaskNotify(Task task, String scene) {
        if (!props.isEnabled()) {
            log.info("企微对接未启用，跳过推送 taskId={}, scene={}", task.getId(), scene);
            return new SendResult(0, "wecom disabled");
        }
        if (task.getOwnerUserid() == null || task.getOwnerUserid().isBlank()) {
            log.info("事项无负责人企微userid，跳过推送 taskId={}", task.getId());
            return new SendResult(0, "no owner userid");
        }
        String content = buildContent(task, scene);
        SendResult r = sendTextMessage(task.getOwnerUserid(), content);

        NotifyLog nl = new NotifyLog();
        nl.setTaskId(task.getId());
        nl.setTouser(task.getOwnerUserid());
        nl.setContent(content);
        nl.setResult(r.ok() ? "SENT" : "FAILED");
        nl.setErrcode(r.errcode());
        nl.setErrmsg(truncate(r.errmsg()));
        notifyLogRepository.save(nl);

        task.setNotifyStatus(r.ok() ? "SENT" : "FAILED");
        log.info("企微推送完成 taskId={}, scene={}, result={}, errcode={}",
                task.getId(), scene, nl.getResult(), r.errcode());
        return r;
    }

    private String buildContent(Task task, String scene) {
        String prefix = switch (scene) {
            case "create" -> "【新待办】";
            case "assign" -> "【待办分配】";
            case "status" -> "【待办更新】";
            default -> "【待办通知】";
        };
        String code = task.getTaskCode() != null && !task.getTaskCode().isBlank()
                ? task.getTaskCode() : String.valueOf(task.getId());
        String deadline = task.getDeadline() != null ? task.getDeadline().toString() : "未设置";
        return prefix + "#" + code + " " + task.getTitle()
                + "\n状态：" + task.getStatus()
                + "\n优先级：" + task.getPriority()
                + "\n截止：" + deadline
                + "\n下一步：" + (task.getNextStep() != null ? task.getNextStep() : "—");
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 255 ? s.substring(0, 255) : s;
    }
}
