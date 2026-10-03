package com.myexampleproject.userservice.repository;

import com.myexampleproject.userservice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

//Tham số đầu tiên. Đây là tên của lớp Entity
//Long: Tham số thứ hai. Đây là kiểu dữ liệu của Khóa Chính
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByKeycloakId(String keycloakId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.keycloakId=:owner")
    Optional<User> lockOwner(@org.springframework.data.repository.query.Param("owner") String owner);
    Optional<User> findByEmail(String email);
}
