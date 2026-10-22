package com.todoapp.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Jackson 严格模式（AD-8）：在 Boot 自动配置的 {@code JsonMapper} 上追加定制，不另建 mapper bean
 * （Boot 默认关闭 FAIL_ON_UNKNOWN_PROPERTIES，未知字段一律忽略靠的就是它）。
 *
 * <ul>
 *   <li>关闭 {@code ALLOW_COERCION_OF_SCALARS}：{@code "5"} 不再强转为 Integer、{@code "true"} 不再强转为 Boolean；</li>
 *   <li>文本类目标拒绝数字和布尔：{@code 123}、{@code 1.5}、{@code true} 不再强转为 String。</li>
 * </ul>
 * 被拒的输入抛 {@code HttpMessageNotReadableException}，由 {@code ApiErrorHandler} 映射为 400 VALIDATION_ERROR。
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer strictScalars() {
        return builder -> builder
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, cfg -> cfg
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
