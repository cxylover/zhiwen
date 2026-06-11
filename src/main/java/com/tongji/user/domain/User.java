package com.tongji.user.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private Long id;// 用户id
    private String phone;// 手机号
    private String email;// 邮箱
    private String passwordHash;// 密码哈希
    private String nickname;// 昵称
    private String avatar;// 头像
    private String bio;// 个人简介
    private String zgId;//知文ID
    private String gender;// 性别
    private LocalDate birthday;// 生日
    private String school;// 学校
    private String tagsJson;// 标签
    private Instant createdAt;// 创建时间
    private Instant updatedAt;// 更新时间
}

