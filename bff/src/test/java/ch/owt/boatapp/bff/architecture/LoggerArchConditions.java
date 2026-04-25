package ch.owt.boatapp.bff.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Custom ArchUnit conditions for the project's logging convention: every
 * {@code @ControllerAdvice} (or analogous) class declares an SLF4J
 * {@link org.slf4j.Logger} field, and every {@code @ExceptionHandler} method
 * inside it calls that logger.
 *
 * <p>These predicates are not built into ArchUnit. Keeping them in the
 * service-local test sources (one copy per service) avoids a shared test
 * module for two services and makes the rules immediately visible alongside
 * the architecture tests that use them.
 */
public final class LoggerArchConditions {

    private LoggerArchConditions() {}

    /**
     * Build a class-level condition asserting that the class declares at
     * least one field whose static type is assignable to {@code fieldType}.
     *
     * @param fieldType the required field type (typically
     *                  {@code org.slf4j.Logger.class})
     * @return an {@link ArchCondition} that violates with a message of the
     *         form {@code "<className> does not declare a field of type
     *         <fieldType>"} when no such field is found
     */
    public static ArchCondition<JavaClass> haveAFieldOfType(Class<?> fieldType) {
        return new ArchCondition<>("have a field of type " + fieldType.getName()) {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                boolean ok = clazz.getFields().stream()
                        .anyMatch(f -> f.getRawType().isAssignableTo(fieldType));
                if (!ok) {
                    events.add(SimpleConditionEvent.violated(clazz,
                            clazz.getName() + " does not declare a field of type "
                                    + fieldType.getName()));
                }
            }
        };
    }

    /**
     * Build a method-level condition asserting that the method body invokes
     * at least one method on an {@link org.slf4j.Logger}.
     *
     * <p>Used together with a selector like {@code methods().that()
     * .areAnnotatedWith(ExceptionHandler.class)} to guarantee that every
     * exception handler emits a log record.
     *
     * @return an {@link ArchCondition} that violates with a message of the
     *         form {@code "<methodFullName> never calls an org.slf4j.Logger
     *         method"} when no logger call is found
     */
    public static ArchCondition<JavaMethod> callALoggerMethod() {
        return new ArchCondition<>("call an org.slf4j.Logger method") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean ok = method.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner()
                                .isAssignableTo(org.slf4j.Logger.class));
                if (!ok) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName()
                                    + " never calls an org.slf4j.Logger method"));
                }
            }
        };
    }
}
