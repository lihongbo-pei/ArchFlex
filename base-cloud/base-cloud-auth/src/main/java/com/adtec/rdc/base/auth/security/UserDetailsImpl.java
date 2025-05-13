package com.adtec.rdc.base.auth.security;

import com.adtec.rdc.base.common.enums.UserStatusEnum;
import com.adtec.rdc.base.common.model.vo.SysRoleVo;
import com.adtec.rdc.base.common.model.vo.SysUserVo;

import lombok.Data;
import org.apache.commons.lang.StringUtils;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author: JTao
 * @date: 2018/10/9 10:09
 * @description: security 用户对象
 */
@Data
public class UserDetailsImpl implements UserDetails {

    private static final long serialVersionUID = -2636609458742965698L;

    private String userId;
    private String username;
    private String password;
    private String status;
    private List<SysRoleVo> roleVos;

    public UserDetailsImpl(SysUserVo userVo) {
        this.userId = userVo.getUserId();
        this.username = userVo.getLoginName();
        this.password = userVo.getLoginPwd();
        this.status = userVo.getDelFlag();
        this.roleVos = userVo.getSysRoleVoList();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> authorityList = new ArrayList<>();
        roleVos.forEach(role -> {
            authorityList.add(new SimpleGrantedAuthority(role.getRoleCode()+"="+role.getRoleId()));
        });
        //添加公共角色(此角色没有任何权限, 前端判断用户是否加载完成时使用)
        authorityList.add(new SimpleGrantedAuthority("SYSTEM_DEFAULT_ROLE_TO_USER=SYSTEM_DEFAULT_ROLE_TO_USER"));
        return authorityList;
    }

    @Override
    public String getPassword() {
        return this.password;
    }

    @Override
    public String getUsername() {
        return this.username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !StringUtils.equals(UserStatusEnum.LOCK.getCode(), status);
    }
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return StringUtils.equals(UserStatusEnum.NORMAL.getCode(), status);
    }
}
