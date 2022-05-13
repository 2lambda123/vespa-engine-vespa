package com.yahoo.vespa.test.samples;

import com.yahoo.vespa.testrunner.Expect;
import org.junit.jupiter.api.Test;

@Expect(error = 2)
public class FailingInstantiationTest {

    final int i = Integer.parseInt("");

    @Test
    void test() { }

    @Test
    void fest() { }

}
