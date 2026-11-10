package io.dotflows.sample;

import io.dotflows.delegate.DelegateInput;
import io.dotflows.delegate.FlowDelegate;
import io.dotflows.delegate.TaskContext;
import io.dotflows.delegate.TaskDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stands in for a real mail integration (SES, SendGrid, JavaMailSender…): it only logs. */
@Component
@FlowDelegate(value = "sendEmail", description = "Sends an email to one recipient",
        inputs = {
                @DelegateInput(name = "to", description = "Recipient address", required = true),
                @DelegateInput(name = "subject", description = "Subject line", required = true),
                @DelegateInput(name = "body", description = "Plain-text body")
        })
public class SendEmailDelegate implements TaskDelegate {

    private static final Logger log = LoggerFactory.getLogger(SendEmailDelegate.class);

    @Override
    public void execute(TaskContext context) {
        String to = context.input("to", String.class);
        if (to == null || !to.contains("@")) {
            throw new IllegalArgumentException("'" + to + "' is not an email address");
        }
        // A real provider would receive context.idempotencyKey() so a retried task cannot send twice.
        log.info("EMAIL to={} subject={} body={} (idempotency key {})",
                to, context.input("subject"), context.input("body"), context.idempotencyKey());
        context.setVariable("emailMessageId", "email-" + Math.abs(context.idempotencyKey().hashCode()));
    }
}
