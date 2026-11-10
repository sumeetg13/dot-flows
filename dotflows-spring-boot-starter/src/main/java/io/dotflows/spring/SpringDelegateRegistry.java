package io.dotflows.spring;

import io.dotflows.delegate.DelegateDescriptor;
import io.dotflows.delegate.DelegateRegistry;
import io.dotflows.delegate.FlowDelegate;
import io.dotflows.delegate.SimpleDelegateRegistry;
import io.dotflows.delegate.TaskDelegate;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.List;
import java.util.Optional;

/** Finds every bean annotated with {@link FlowDelegate} once the application context is fully built. */
public class SpringDelegateRegistry implements DelegateRegistry, SmartInitializingSingleton {

    private final ListableBeanFactory beans;
    private final SimpleDelegateRegistry registry = new SimpleDelegateRegistry();

    public SpringDelegateRegistry(ListableBeanFactory beans) {
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (String beanName : beans.getBeanNamesForAnnotation(FlowDelegate.class)) {
            FlowDelegate annotation = beans.findAnnotationOnBean(beanName, FlowDelegate.class);
            Object bean = beans.getBean(beanName);
            if (!(bean instanceof TaskDelegate delegate)) {
                throw new IllegalStateException("Bean '" + beanName + "' is annotated with @FlowDelegate but does not "
                        + "implement " + TaskDelegate.class.getName());
            }
            registry.register(DelegateDescriptor.of(annotation), delegate);
        }
    }

    @Override
    public Optional<TaskDelegate> find(String name) {
        return registry.find(name);
    }

    @Override
    public List<DelegateDescriptor> list() {
        return registry.list();
    }
}
