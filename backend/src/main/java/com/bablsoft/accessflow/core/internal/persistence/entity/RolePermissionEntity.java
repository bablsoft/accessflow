package com.bablsoft.accessflow.core.internal.persistence.entity;

import com.bablsoft.accessflow.core.api.Permission;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** One permission granted to a role (AF-522). Composite key {@code (role_id, permission)}. */
@Entity
@Table(name = "role_permissions")
@IdClass(RolePermissionEntity.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class RolePermissionEntity {

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private RoleEntity role;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 100)
    private Permission permission;

    public RolePermissionEntity(RoleEntity role, Permission permission) {
        this.role = role;
        this.permission = permission;
    }

    public static class Key implements Serializable {

        private UUID role;
        private Permission permission;

        public Key() {
        }

        public Key(UUID role, Permission permission) {
            this.role = role;
            this.permission = permission;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key other
                    && Objects.equals(role, other.role)
                    && permission == other.permission;
        }

        @Override
        public int hashCode() {
            return Objects.hash(role, permission);
        }
    }
}
