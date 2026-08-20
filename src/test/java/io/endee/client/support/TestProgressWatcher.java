package io.endee.client.support;

import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

// Prints PASS/FAIL/ABORTED/SKIP for every test method. Auto-registered suite-wide via META-INF/services.
public class TestProgressWatcher implements TestWatcher {

  private static String label(ExtensionContext context) {
    String className = context.getTestClass().map(Class::getSimpleName).orElse("?");
    return className + "." + context.getDisplayName();
  }

  @Override
  public void testSuccessful(ExtensionContext context) {
    System.out.println("[PASS] " + label(context));
  }

  @Override
  public void testFailed(ExtensionContext context, Throwable cause) {
    System.out.println("[FAIL] " + label(context) + " -> " + cause);
  }

  @Override
  public void testAborted(ExtensionContext context, Throwable cause) {
    System.out.println("[ABORTED] " + label(context) + " -> " + cause);
  }

  @Override
  public void testDisabled(ExtensionContext context, Optional<String> reason) {
    System.out.println("[SKIP] " + label(context) + reason.map(r -> " -> " + r).orElse(""));
  }
}
