package com.adtec.rdc.base.auth.config;

import com.adtec.rdc.base.auth.handler.CustomWebResponseExceptionTranslator;
import com.adtec.rdc.base.auth.security.UserDetailsImpl;
import com.adtec.rdc.base.common.constants.ServiceNameConstants;
import com.adtec.rdc.base.common.base.service.MessageQueueService;
import com.adtec.rdc.base.common.constants.MqQueueNameConstant;
import com.adtec.rdc.base.common.constants.SecurityConstants;
import com.adtec.rdc.base.common.constants.UserConstants;
import com.adtec.rdc.base.common.enums.OperationStatusEnum;
import com.adtec.rdc.base.common.enums.SysLogTypeEnum;
import com.adtec.rdc.base.common.model.bo.SysOperlog;
import com.adtec.rdc.base.common.util.UrlUtil;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.oauth2.common.DefaultOAuth2AccessToken;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.config.annotation.configurers.ClientDetailsServiceConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configuration.AuthorizationServerConfigurerAdapter;
import org.springframework.security.oauth2.config.annotation.web.configuration.EnableAuthorizationServer;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerEndpointsConfigurer;
import org.springframework.security.oauth2.config.annotation.web.configurers.AuthorizationServerSecurityConfigurer;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.client.JdbcClientDetailsService;
import org.springframework.security.oauth2.provider.token.TokenEnhancer;
import org.springframework.security.oauth2.provider.token.TokenEnhancerChain;
import org.springframework.security.oauth2.provider.token.store.JwtAccessTokenConverter;
import org.springframework.security.oauth2.provider.token.store.redis.RedisTokenStore;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * @author: LittleLee
 * @date: 2025/06/8 17:27
 * @description: oauth2认证服务器配置类
 */
@Slf4j
@Configuration
@EnableAuthorizationServer
public class OAuth2AuthorizationServerConfig extends AuthorizationServerConfigurerAdapter {
    @Autowired
    private MessageQueueService messageQueueService;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    @Autowired
    private CustomWebResponseExceptionTranslator customWebResponseExceptionTranslator;

    @Autowired
    private DataSource dataSource;

    /**
     * 配置token存储到redis中
     * @return
     */
    @Bean
    public RedisTokenStore redisTokenStore() {
        RedisTokenStore store = new RedisTokenStore(redisConnectionFactory);
        store.setPrefix(SecurityConstants.CLOUD_PREFIX);
        return store;
    }

    /**
     * 配置client通过jdbc从数据库查询
     * @param clients
     * @throws Exception
     */
    @Override
    public void configure(ClientDetailsServiceConfigurer clients) throws Exception {
        JdbcClientDetailsService clientDetailsService = new JdbcClientDetailsService(dataSource);
        clientDetailsService.setSelectClientDetailsSql(SecurityConstants.DEFAULT_FIND_STATEMENT_BY_CLIENT_ID);
        clientDetailsService.setFindClientDetailsSql(SecurityConstants.DEFAULT_FIND_STATEMENT);
        clients.withClientDetails(clientDetailsService);
    }

    /**
     * 配置授权(authorization)以及令牌(token)的访问端点和令牌服务(token services)。
     */
    @Override
    public void configure(AuthorizationServerEndpointsConfigurer endpoints) throws Exception {
        // token增强链
        TokenEnhancerChain tokenEnhancerChain = new TokenEnhancerChain();
        // 把jwt增强，与额外信息增强加入到增强链
        tokenEnhancerChain.setTokenEnhancers(Arrays.asList(tokenEnhancer(), jwtAccessTokenConverter()));
        endpoints
                .authenticationManager(authenticationManager)
                .tokenStore(redisTokenStore())
                .tokenEnhancer(tokenEnhancerChain)
                .reuseRefreshTokens(false);
        // 添加认证异常处理器
        endpoints.exceptionTranslator(customWebResponseExceptionTranslator);
    }

    /**
     * 配置令牌端点(Token Endpoint)的安全约束
     */
    @Override
    public void configure(AuthorizationServerSecurityConfigurer security) throws Exception {
        security
                // 允许表单认证请求
                .allowFormAuthenticationForClients()
                // spel表达式 访问公钥端点（/auth/token_key）需要认证
                .tokenKeyAccess("isAuthenticated()")
                // spel表达式 访问令牌解析端点（/auth/check_token）需要认证
                .checkTokenAccess("isAuthenticated()");
    }

    @Bean
    public JwtAccessTokenConverter jwtAccessTokenConverter() {
        JwtAccessTokenConverter jwtAccessTokenConverter = new JwtAccessTokenConverter();
        jwtAccessTokenConverter.setSigningKey(SecurityConstants.SIGN_KEY);
        return jwtAccessTokenConverter;
    }

    /**
     * jwt token增强，添加额外信息
     * @return
     */
    @Bean
    public TokenEnhancer tokenEnhancer() {
        return new TokenEnhancer() {
            @Override
            public OAuth2AccessToken enhance(OAuth2AccessToken oAuth2AccessToken, OAuth2Authentication oAuth2Authentication) {
                // 添加额外信息的map
                final Map<String, Object> additionMessage = new HashMap<>(2);
                // 获取当前登录的用户
                UserDetailsImpl user = (UserDetailsImpl) oAuth2Authentication.getUserAuthentication().getPrincipal();
                // 登录日志记录
                HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
                SysOperlog operlog = new SysOperlog();
                operlog
                        .setCreateBy(user.getUsername())
                        .setRequestUri(request.getRequestURI())
                        .setUserAgent(request.getHeader("user-agent"))
                        .setLogType(SysLogTypeEnum.LOGIN.getCode())
                        .setLogStatus(OperationStatusEnum.SUCCESS.getCode())
                        .setModuleName("auth认证模块")
                        .setActionName("登录")
                        .setServiceId(ServiceNameConstants.BASE_CLOUD_AUTH)
                        .setRemoteAddr(UrlUtil.getRemoteHost(request))
                        .setMethodName(request.getMethod());
                messageQueueService.convertAndSend(MqQueueNameConstant.SYS_LOG_QUEUE, operlog);
                log.info("当前用户为：{}", user);
                // 如果用户不为空 则把id放入jwt token中
                if (user != null) {
                    additionMessage.put(UserConstants.USER_ID, user.getUserId());
                    additionMessage.put(UserConstants.LOGIN_NAME, user.getUsername());
                }
                ((DefaultOAuth2AccessToken) oAuth2AccessToken).setAdditionalInformation(additionMessage);
                return oAuth2AccessToken;
            }
        };
    }

}
