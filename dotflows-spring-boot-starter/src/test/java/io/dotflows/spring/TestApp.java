package io.dotflows.spring;

import io.dotflows.delegate.DelegateInput;
import io.dotflows.delegate.FlowDelegate;
import io.dotflows.delegate.TaskContext;
import io.dotflows.delegate.TaskDelegate;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootApplication
public class TestApp {

    @Component
    @FlowDelegate(value = "sendEmail", description = "Pretends to send an email",
            inputs = {@DelegateInput(name = "to", description = "Recipient", required = true)})
    public static class SendEmail implements TaskDelegate {

        public final List<String> sent = new CopyOnWriteArrayList<>();

        @Override
        public void execute(TaskContext context) {
            sent.add(context.input("to", String.class));
            context.setVariable("emailed", true);
        }
    }

    @Component
    @FlowDelegate("failUntilThird")
    public static class FailUntilThird implements TaskDelegate {

        public final AtomicInteger calls = new AtomicInteger();

        @Override
        public void execute(TaskContext context) {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("not yet");
            }
        }
    }
}
