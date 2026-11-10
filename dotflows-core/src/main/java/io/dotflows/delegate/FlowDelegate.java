package io.dotflows.delegate;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names a {@link TaskDelegate} so processes can refer to it, and describes it for the modeler.
 *
 * <pre>{@code
 * @Component
 * @FlowDelegate(value = "sendEmail", description = "Sends an email",
 *         inputs = {@DelegateInput(name = "to", required = true), @DelegateInput(name = "subject")})
 * class SendEmailDelegate implements TaskDelegate { ... }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FlowDelegate {

    /** The name processes use to refer to this delegate. */
    String value();

    String description() default "";

    DelegateInput[] inputs() default {};
}
