package com.example.wecom.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 企微回调凭据 KV 存取：写入覆盖最新值，读取不存在返回 null。
 */
@Service
public class KvStoreService {

    private static final Logger log = LoggerFactory.getLogger(KvStoreService.class);

    /** 企微每10分钟推送的 suite_ticket（获取 suite_access_token 的凭证，重启后从库恢复） */
    public static final String KEY_SUITE_TICKET = "suite_ticket";
    /** 第三方应用凭证 suite_access_token */
    public static final String KEY_SUITE_ACCESS_TOKEN = "suite_access_token";
    /** 授权成功通知中的企业临时授权码（10分钟内有效，单次使用） */
    public static final String KEY_AUTH_CODE = "auth_code";
    /** 企业永久授权码 */
    public static final String KEY_PERMANENT_CODE = "permanent_code";
    /** 授权企业 corpid（数据回调解密用） */
    public static final String KEY_AUTHORIZED_CORP_ID = "authorized_corpid";

    private final KeyValueStoreRepository repository;

    public KvStoreService(KeyValueStoreRepository repository) {
        this.repository = repository;
    }

    /** 写入（已存在则覆盖 value 并更新时间） */
    @Transactional
    public void put(String key, String value) {
        KeyValueStore row = repository.findByKeyStr(key).orElseGet(KeyValueStore::new);
        row.setKeyStr(key);
        row.setValue(value);
        repository.save(row);
        log.info("KV 已写入 key={}", key);
    }

    /** 读取，不存在返回 null */
    @Transactional(readOnly = true)
    public String get(String key) {
        return repository.findByKeyStr(key).map(KeyValueStore::getValue).orElse(null);
    }
}
