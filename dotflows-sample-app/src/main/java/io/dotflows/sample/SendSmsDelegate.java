package io.dotflows.sample;

import io.dotflows.delegate.DelegateInput;
import io.dotflows.delegate.FlowDelegate;
import io.dotflows.delegate.TaskContext;
import io.dotflows.delegate.TaskDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stands in for a real SMS integration (Twilio, SNS…): it only logs. */
@Component
@FlowDelegate(value = "sendSms", description = "Sends a text message",
        inputs = {
                @DelegateInput(name = "to", description = "Phone number in international format", required = true),
                @DelegateInput(name = "text", description = "Message text", required = true)
        })
public class SendSmsDelegate implements TaskDelegate {

    private static final Logger log = LoggerFactory.getLogger(SendSmsDelegate.class);

    @Override
    public void execute(TaskContext context) {
        String to = context.input("to", String.class);
        if (to == null || !to.startsWith("+")) {
            throw new IllegalArgumentException("'" + to + "' is not a phone number in international format");
        }
        log.info("SMS to={} text={} (idempotency key {})", to, context.input("text"), context.idempotencyKey());
        context.setVariable("smsMessageId", "sms-" + Math.abs(context.idempotencyKey().hashCode()));
    }
}
