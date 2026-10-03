package com.myexampleproject.userservice.repository;

import com.myexampleproject.userservice.model.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {
    List<UserAddress> findAllByUserKeycloakIdOrderByIsDefaultDescUpdatedDateDesc(String userKeycloakId);
    Optional<UserAddress> findFirstByUserKeycloakIdOrderByIdAsc(String owner);
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically=true,clearAutomatically=true)
    @org.springframework.data.jpa.repository.Query("update UserAddress a set a.isDefault=false where a.userKeycloakId=:owner and a.isDefault=true")
    int clearDefaults(@org.springframework.data.repository.query.Param("owner") String owner);
    Optional<UserAddress> findByIdAndUserKeycloakId(Long id, String userKeycloakId);
}
