/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright (c) 1999-2026, Algorithmx Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.rulii.test.bind;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.rulii.bind.Binding;
import org.rulii.bind.Bindings;
import org.rulii.bind.ScopedBindings;
import org.rulii.context.RuleContext;
import org.rulii.rule.Rule;
import org.rulii.ruleset.RuleSet;

import static org.rulii.model.action.Actions.action;
import static org.rulii.model.condition.Conditions.condition;

/**
 * Tests for original-value tracking on Bindings: getOriginalValue(), isModified(),
 * resetOriginal() and RuleSetBuilder.resetOriginals(boolean).
 */
public class BindingVersioningTest {

    private static final double EPS = 1e-9;

    // -----------------------------------------------------------------------
    // Binding-level original-value tracking
    // -----------------------------------------------------------------------

    @Test
    public void testOriginalEqualsValueWhenNeverSet() {
        Bindings bindings = Bindings.builder().standard();
        Binding<Integer> x = bindings.bind("x", Integer.class, 5);

        Assertions.assertEquals(5, x.getValue());
        // No explicit setValue(): original mirrors the current value.
        Assertions.assertEquals(5, x.getOriginalValue());
        Assertions.assertFalse(x.isModified());
    }

    @Test
    public void testOriginalCapturedOnFirstSetValue() {
        Bindings bindings = Bindings.builder().standard();
        Binding<Double> total = bindings.bind("total", 250.0);

        total.setValue(225.0);

        Assertions.assertEquals(225.0, total.getValue(), EPS);
        Assertions.assertEquals(250.0, total.getOriginalValue(), EPS);
        Assertions.assertTrue(total.isModified());
    }

    @Test
    public void testOriginalStableAcrossMultipleSets() {
        Bindings bindings = Bindings.builder().standard();
        Binding<Double> total = bindings.bind("total", 250.0);

        total.setValue(225.0);
        total.setValue(212.5);

        // The original is captured once (on the first mutation) and stays put.
        Assertions.assertEquals(250.0, total.getOriginalValue(), EPS);
        Assertions.assertEquals(212.5, total.getValue(), EPS);
        Assertions.assertTrue(total.isModified());
    }

    @Test
    public void testResetOriginalClearsModified() {
        Bindings bindings = Bindings.builder().standard();
        Binding<Double> total = bindings.bind("total", 250.0);

        total.setValue(225.0);
        Assertions.assertTrue(total.isModified());

        total.resetOriginal();

        Assertions.assertFalse(total.isModified());
        // After reset the original is the current value.
        Assertions.assertEquals(225.0, total.getOriginalValue(), EPS);
        Assertions.assertEquals(225.0, total.getValue(), EPS);
    }

    @Test
    public void testSetAfterResetRecaptures() {
        Bindings bindings = Bindings.builder().standard();
        Binding<Double> total = bindings.bind("total", 250.0);

        total.setValue(225.0);   // original -> 250.0
        total.resetOriginal();   // original -> 225.0, modified -> false
        total.setValue(200.0);   // original re-captured from the reset point

        Assertions.assertEquals(225.0, total.getOriginalValue(), EPS);
        Assertions.assertEquals(200.0, total.getValue(), EPS);
        Assertions.assertTrue(total.isModified());
    }

    @Test
    public void testOriginalNullWhenBoundWithoutValue() {
        Bindings bindings = Bindings.builder().standard();
        Binding<String> s = bindings.bind("s", String.class);

        Assertions.assertNull(s.getValue());
        Assertions.assertNull(s.getOriginalValue());
        Assertions.assertFalse(s.isModified());

        s.setValue("hello");

        Assertions.assertEquals("hello", s.getValue());
        // The pre-mutation value (null) is the original.
        Assertions.assertNull(s.getOriginalValue());
        Assertions.assertTrue(s.isModified());
    }

    // -----------------------------------------------------------------------
    // RuleSet-level resetOriginals(...) behaviour
    // -----------------------------------------------------------------------

    @Test
    public void testRuleSetResetOriginalsTrue() {
        // Rebates: each rule reads the ORIGINAL price (250.0) for its discount.
        RuleSet<?> rebatesRuleSet = RuleSet.builder()
                .with("Rebates", "Rebates")
                .rule(Rule.builder()
                        .name("Rebate1")
                        .given(condition(() -> true))
                        .then(action((Binding<Double> total) -> total.setValue(total.getValue() * 0.9)))
                        .build())
                .rule(Rule.builder()
                        .name("Rebate2")
                        .given(condition(() -> true))
                        .then(action((Binding<Double> total) ->
                                total.setValue(total.getValue() - total.getOriginalValue() * 0.05)))
                        .build())
                .build();

        // Discounts: resetOriginals(true) makes the post-rebate total the new original.
        RuleSet<?> discountRuleSet = RuleSet.builder()
                .with("Discounts", "Discounts")
                .resetOriginals(true)
                .rule(Rule.builder()
                        .name("Discount1")
                        .given(condition(() -> true))
                        .then(action((Binding<Double> total) -> total.setValue(total.getOriginalValue() * 0.9)))
                        .build())
                .build();

        Bindings cart = Bindings.builder().standard();
        cart.bind("total", 250.0);
        cart.bind("discount", 0.0);
        cart.bind("rebates", 0.0);

        rebatesRuleSet.run(cart);
        // 250.0 -> 225.0 (Rebate1) -> 225.0 - 250.0*0.05 = 212.5 (Rebate2)
        Assertions.assertEquals(212.5, (Double) cart.getValue("total"), EPS);

        discountRuleSet.run(cart);
        // original reset to 212.5, then 212.5 * 0.9 = 191.25
        Assertions.assertEquals(191.25, (Double) cart.getValue("total"), EPS);
    }

    @Test
    public void testRuleSetWithoutResetKeepsOriginal() {
        RuleSet<?> rebatesRuleSet = RuleSet.builder()
                .with("Rebates", "Rebates")
                .rule(Rule.builder()
                        .name("Rebate1")
                        .given(condition(() -> true))
                        .then(action((Binding<Double> total) -> total.setValue(total.getValue() * 0.9)))
                        .build())
                .build();

        Bindings cart = Bindings.builder().standard();
        cart.bind("total", 250.0);

        rebatesRuleSet.run(cart);

        Binding<Double> total = cart.getBinding("total");
        // No resetOriginals(): the original stays at the pre-run value.
        Assertions.assertEquals(250.0, total.getOriginalValue(), EPS);
        Assertions.assertEquals(225.0, total.getValue(), EPS);
        Assertions.assertTrue(total.isModified());
    }

    @Test
    public void testResetOriginalsScopedToCurrentScopeOnly() {
        // Two scopes: a parent ("global") binding and a current-scope binding, both mutated.
        RuleContext ctx = RuleContext.builder().build(Bindings.builder().standard());
        ScopedBindings bindings = ctx.getBindings();
        bindings.bind("parentVal", 100.0);  // lives in the parent (global) scope
        bindings.addScope("work");
        bindings.bind("localVal", 200.0);   // lives in the current scope

        Binding<Double> parent = bindings.getBinding("parentVal");
        Binding<Double> local = bindings.getBinding("localVal");
        parent.setValue(90.0);              // parent original -> 100.0
        local.setValue(180.0);             // local original  -> 200.0

        RuleSet<?> ruleSet = RuleSet.builder()
                .with("ScopedReset")
                .resetOriginals(true)
                .rule(Rule.builder().build(condition(() -> true)))
                .build();
        ruleSet.run(ctx);

        // Current-scope binding is reset to its current value...
        Assertions.assertEquals(180.0, local.getOriginalValue(), EPS);
        Assertions.assertFalse(local.isModified());
        // ...but the inherited parent-scope binding is left untouched.
        Assertions.assertEquals(100.0, parent.getOriginalValue(), EPS);
        Assertions.assertTrue(parent.isModified());
    }

    @Test
    public void testResetOriginalsAppliesUnderRunAsync() throws Exception {
        RuleSet<?> rebatesRuleSet = RuleSet.builder()
                .with("Rebates")
                .rule(Rule.builder()
                        .name("Rebate1")
                        .given(condition(() -> true))
                        .then(action((Binding<Double> total) -> total.setValue(total.getValue() * 0.9)))
                        .build())
                .build();
        RuleSet<?> discountRuleSet = RuleSet.builder()
                .with("Discounts")
                .resetOriginals(true)
                .rule(Rule.builder()
                        .name("Discount1")
                        .given(condition(() -> true))
                        .then(action((Binding<Double> total) -> total.setValue(total.getOriginalValue() * 0.9)))
                        .build())
                .build();

        Bindings cart = Bindings.builder().standard();
        cart.bind("total", 250.0);

        rebatesRuleSet.run(cart);  // 250.0 -> 225.0
        // Async execution must honour resetOriginals just like the sync path.
        discountRuleSet.runAsync(RuleContext.builder().build(cart)).get();
        Assertions.assertEquals(202.5, (Double) cart.getValue("total"), EPS);  // 225.0 * 0.9
    }
}
