package io.dotflows.delegate;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares an input a delegate reads, so the modeler can offer a field for it. */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface DelegateInput {

    String name();

    String description() default "";

    boolean required() default false;
}
