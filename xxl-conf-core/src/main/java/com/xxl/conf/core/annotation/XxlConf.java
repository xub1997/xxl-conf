package com.xxl.conf.core.annotation;

import java.lang.annotation.*;

/**
 * xxl conf annotaion  (only support filed)
 *
 * @author xuxueli 2018-02-04 00:34:30
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface XxlConf {

    /**
     * conf key
     *
     * @return
     */
    String value();

    /**
     * conf default value 配置的默认值
     *
     * @return
     */
    String defaultValue() default "";

    /**
     * 当值更改时，是否需要回调刷新。
     *  whether you need a callback refresh, when the value changes.
     *
     * @return
     */
    boolean callback() default true;
}