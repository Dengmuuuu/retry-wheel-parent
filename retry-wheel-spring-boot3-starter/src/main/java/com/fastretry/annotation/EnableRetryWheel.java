package com.fastretry.annotation;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface   EnableRetryWheel {

    /**
     * 是否启动
     */
    boolean value() default true;
}
