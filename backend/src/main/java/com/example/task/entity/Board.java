package com.example.task.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 看板实体（t_board）：支持动态新增看板，身份色/前缀/侧栏排序均可配置。
 */
@Entity
@Table(name = "t_board")
public class Board {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 看板代码（唯一，如 quanfa/happy/temp）。 */
    @Column(name = "code", length = 32, nullable = false)
    private String code;

    /** 看板名称（如 全发/会幸福/临时专项）。 */
    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** 身份色（十六进制，如 #2B59C3）。 */
    @Column(name = "accent", length = 16, nullable = false)
    private String accent = "#2B59C3";

    /** 事项ID前缀（如 QF/HF/LS，1-2 位大写字母）。 */
    @Column(name = "prefix", length = 8, nullable = false)
    private String prefix = "QF";

    /** 侧栏显示排序。 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** 系统看板（1=禁止删除）。 */
    @Column(name = "system_flag", nullable = false)
    private boolean systemFlag = false;

    @Column(name = "created_at", updatable = false, insertable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAccent() {
        return accent;
    }

    public void setAccent(String accent) {
        this.accent = accent;
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        this.prefix = prefix;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isSystemFlag() {
        return systemFlag;
    }

    public void setSystemFlag(boolean systemFlag) {
        this.systemFlag = systemFlag;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
