package com.example.wecom.store;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KeyValueStoreRepository extends JpaRepository<KeyValueStore, Long> {

    Optional<KeyValueStore> findByKeyStr(String keyStr);
}
