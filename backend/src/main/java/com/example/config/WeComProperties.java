package com.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 企业微信应用配置（对应 application.yaml / application-local.yaml 中 wecom.*）。
 * <p>
 * 回调加解密 receiveid 说明：企业(ISV)第三方应用需要 provider-corp-id / suite-id / corp-id；
 * 若为「个人主体」创建的第三方应用，企微回调的 ReceiveId 为空字符串，代码会自动兜底，无需额外配置。
 */
@ConfigurationProperties(prefix = "wecom")
public class WeComProperties {

    /** 是否启用企微对接（推送与回调），默认 true */
    private boolean enabled = true;

    /** 企业ID corpid（数据回调/自建应用加解密用，第三方应用场景下应为授权企业corpid） */
    private String corpId = "";

    /** 服务商自身 corpid（第三方应用 URL 验证(GET)时企业微信用作 receiveid，服务商管理后台-通用开发参数可查） */
    private String providerCorpId = "";

    /** 第三方应用 SuiteID（指令回调解密、获取 suite_access_token 用，服务商管理后台-应用详情可查） */
    private String suiteId = "";

    /** 第三方应用 SuiteSecret（获取 suite_access_token 用） */
    private String suiteSecret = "";

    /** 应用密钥 secret */
    private String secret = "";

    /** 自建应用 AgentId */
    private String agentId = "";

    /** 回调 Token（企微后台「接收消息」处设置） */
    private String token = "";

    /** 回调 EncodingAESKey（企微后台「接收消息」处设置，43位） */
    private String aesKey = "";

    /** 回调相关参数是否已配置完整（token + 43位 aesKey + corpid） */
    public boolean isCallbackReady() {
        return token == null || token.isBlank()
                || aesKey == null || aesKey.length() != 43
                || corpId == null || corpId.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId;
    }

    public String getProviderCorpId() {
        return providerCorpId;
    }

    public void setProviderCorpId(String providerCorpId) {
        this.providerCorpId = providerCorpId;
    }

    public String getSuiteId() {
        return suiteId;
    }

    public void setSuiteId(String suiteId) {
        this.suiteId = suiteId;
    }

    public String getSuiteSecret() {
        return suiteSecret;
    }

    public void setSuiteSecret(String suiteSecret) {
        this.suiteSecret = suiteSecret;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getAesKey() {
        return aesKey;
    }

    public void setAesKey(String aesKey) {
        this.aesKey = aesKey;
    }
}
