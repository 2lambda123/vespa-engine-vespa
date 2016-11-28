// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchlib.rankingexpression;

import com.yahoo.searchlib.rankingexpression.parser.ParseException;
import com.yahoo.searchlib.rankingexpression.rule.CompositeNode;
import com.yahoo.searchlib.rankingexpression.rule.IfNode;
import com.yahoo.searchlib.rankingexpression.rule.ExpressionNode;
import com.yahoo.searchlib.rankingexpression.rule.FunctionNode;
import junit.framework.TestCase;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author <a href="mailto:simon@yahoo-inc.com">Simon Thoresen</a>
 */
public class RankingExpressionTestCase extends TestCase {

    public void testParamInFeature() throws ParseException {
        assertParse("if (1 > 2, dotProduct(allparentid,query(cate1_parentid)), 2)",
                    "if ( 1 > 2,\n" +
                    "dotProduct(allparentid, query(cate1_parentid)),\n" +
                    "2\n" +
                    ")");
    }

    public void testDollarShorthand() throws ParseException {
        assertParse("query(var1)", " $var1");
        assertParse("query(var1)", " $var1 ");
        assertParse("query(var1) + query(var2)", " $var1 + $var2 ");
        assertParse("query(var1) + query(var2) - query(var3)", " $var1 + $var2 - $var3 ");
        assertParse("query(var1) + query(var2) - query(var3) * query(var4) / query(var5)", " $var1 + $var2 - $var3 * $var4 / $var5 ");
        assertParse("(query(var1) + query(var2)) - query(var3) * query(var4) / query(var5)", "($var1 + $var2)- $var3 * $var4 / $var5 ");
        assertParse("query(var1) + (query(var2) - query(var3)) * query(var4) / query(var5)", " $var1 +($var2 - $var3)* $var4 / $var5 ");
        assertParse("query(var1) + query(var2) - (query(var3) * query(var4)) / query(var5)", " $var1 + $var2 -($var3 * $var4)/ $var5 ");
        assertParse("query(var1) + query(var2) - query(var3) * (query(var4) / query(var5))", " $var1 + $var2 - $var3 *($var4 / $var5)");
        assertParse("if (if (f1.out < query(p1), 0, 1) < if (f2.out < query(p2), 0, 1), f3.out, query(p3))", "if(if(f1.out<$p1,0,1)<if(f2.out<$p2,0,1),f3.out,$p3)");
    }

    public void testLookaheadIndefinitely() throws Exception {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<Boolean> future = exec.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                try {
                    new RankingExpression("if (fieldMatch(title) < 0.316316, if (now < 1.218627E9, if (now < 1.217667E9, if (now < 1.217244E9, if (rankBoost < 100050.0, 0.1424368, if (match < 0.284921, if (now < 1.217238E9, 0.1528184, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, if (now < 1.217238E9, 0.1, 0.1493261))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))), 0.1646852)), 0.1850886), if (match < 0.308468, if (firstPhase < 5891.5, 0.08424015, 0.1167076), if (rankBoost < 120050.0, 0.111576, 0.1370456))), if (match < 0.31644, 0.1543837, 0.1727403)), if (now < 1.218088E9, if (now < 1.217244E9, if (fieldMatch(metakeywords).significance < 0.1425405, if (match.totalWeight < 450.0, 0.1712793, 0.1632426), 0.1774488), 0.1895567), if (now < 1.218361E9, if (fieldTermMatch(keywords_1).firstPosition < 1.5, 0.1530005, 0.1370894), 0.1790079)))");
                    return Boolean.TRUE;
                } catch (ParseException e) {
                    return Boolean.FALSE;
                }
            }
        });
        assertTrue(future.get(60, TimeUnit.SECONDS));
    }

    public void testSelfRecursionScript() throws ParseException {
        List<ExpressionFunction> macros = new ArrayList<>();
        macros.add(new ExpressionFunction("foo", null, new RankingExpression("foo")));

        RankingExpression exp = new RankingExpression("foo");
        try {
            exp.getRankProperties(macros);
        } catch (RuntimeException e) {
            assertEquals("Cycle in ranking expression function: [foo[]]", e.getMessage());
        }
    }

    public void testMacroCycleScript() throws ParseException {
        List<ExpressionFunction> macros = new ArrayList<>();
        macros.add(new ExpressionFunction("foo", null, new RankingExpression("bar")));
        macros.add(new ExpressionFunction("bar", null, new RankingExpression("foo")));

        RankingExpression exp = new RankingExpression("foo");
        try {
            exp.getRankProperties(macros);
        } catch (RuntimeException e) {
            assertEquals("Cycle in ranking expression function: [foo[], bar[]]", e.getMessage());
        }
    }

    public void testScript() throws ParseException {
        List<ExpressionFunction> macros = new ArrayList<>();
        macros.add(new ExpressionFunction("foo", Arrays.asList("arg1", "arg2"), new RankingExpression("min(arg1, pow(arg2, 2))")));
        macros.add(new ExpressionFunction("bar", Arrays.asList("arg1", "arg2"), new RankingExpression("arg1 * arg1 + 2 * arg1 * arg2 + arg2 * arg2")));
        macros.add(new ExpressionFunction("baz", Arrays.asList("arg1", "arg2"), new RankingExpression("foo(1, 2) / bar(arg1, arg2)")));
        macros.add(new ExpressionFunction("cox", null, new RankingExpression("10 + 08 * 1977")));

        assertScript("foo(1,2) + foo(3,4) * foo(5, foo(foo(6, 7), 8))", macros,
                     Arrays.asList(
                             "rankingExpression(foo@e2dc17a89864aed0.12232eb692c6c502) + rankingExpression(foo@af74e3fd9070bd18.a368ed0a5ba3a5d0) * rankingExpression(foo@dbab346efdad5362.e5c39e42ebd91c30)",
                             "min(5,pow(rankingExpression(foo@d1d1417259cdc651.573bbcd4be18f379),2))",
                             "min(6,pow(7,2))",
                             "min(1,pow(2,2))",
                             "min(3,pow(4,2))",
                             "min(rankingExpression(foo@84951be88255b0ec.d0303e061b36fab8),pow(8,2))"
                     ));
        assertScript("foo(1, 2) + bar(3, 4)", macros,
                     Arrays.asList(
                             "rankingExpression(foo@e2dc17a89864aed0.12232eb692c6c502) + rankingExpression(bar@af74e3fd9070bd18.a368ed0a5ba3a5d0)",
                             "min(1,pow(2,2))",
                             "3 * 3 + 2 * 3 * 4 + 4 * 4"
                     ));
        assertScript("baz(1, 2)", macros,
                     Arrays.asList(
                             "rankingExpression(baz@e2dc17a89864aed0.12232eb692c6c502)",
                             "min(1,pow(2,2))",
                             "rankingExpression(foo@e2dc17a89864aed0.12232eb692c6c502) / rankingExpression(bar@e2dc17a89864aed0.12232eb692c6c502)",
                             "1 * 1 + 2 * 1 * 2 + 2 * 2"
                     ));
        assertScript("cox", macros,
                     Arrays.asList(
                             "rankingExpression(cox)",
                             "10 + 08 * 1977"
                     ));
    }

    public void testBug3464208() throws ParseException {
        List<ExpressionFunction> macros = new ArrayList<>();
        macros.add(new ExpressionFunction("log10tweetage", null, new RankingExpression("69")));

        String lhs = "log10(0.01+attribute(user_followers_count)) * log10(socialratio) * " +
                     "log10(userage/(0.01+attribute(user_statuses_count)))";
        String rhs = "(log10tweetage * log10tweetage * log10tweetage) + 5.0 * " +
                     "attribute(ythl)";

        String expLhs = "log10(0.01 + attribute(user_followers_count)) * log10(socialratio) * " +
                        "log10(userage / (0.01 + attribute(user_statuses_count)))";
        String expRhs = "(rankingExpression(log10tweetage) * rankingExpression(log10tweetage) * " +
                        "rankingExpression(log10tweetage)) + 5.0 * attribute(ythl)";

        assertScript(lhs + " + " + rhs, macros,
                     Arrays.asList(
                             expLhs + " + " + expRhs,
                             "69"
                     ));
        assertScript(lhs + " - " + rhs, macros,
                     Arrays.asList(
                             expLhs + " - " + expRhs,
                             "69"
                     ));
    }

    public void testParse() throws ParseException, IOException {
        BufferedReader reader = new BufferedReader(new FileReader("src/tests/rankingexpression/rankingexpressionlist"));
        String line;
        int lineNumber = 0;
        while ((line = reader.readLine()) != null) {
            lineNumber++;
            if (line.length() == 0 || line.charAt(0) == '#') {
                continue;
            }
            String[] parts = line.split(";");
            // System.out.println("Parsing '" + parts[0].trim() + "'..");
            RankingExpression expression = new RankingExpression(parts[0].trim());

            String out = expression.toString();
            if (parts.length == 1) {
                assertEquals(parts[0].trim(), out);
            } else {
                boolean ok = false;
                String err = "Expression '" + out + "' not present in { ";
                for (int i = 1; i < parts.length && !ok; ++i) {
                    err += "'" + parts[i].trim() + "'";
                    if (parts[i].trim().equals(out)) {
                        ok = true;
                    }
                    if (i < parts.length - 1) {
                        err += ", ";
                    }
                }
                err += " }.";
                assertTrue("At line " + lineNumber + ": " + err, ok);
            }
        }
    }

    public void testIssue() throws ParseException {
        assertEquals("feature.0", new RankingExpression("feature.0").toString());
        assertEquals("if (1 > 2, 3, 4) + feature(arg1).out.out",
                     new RankingExpression("if ( 1 > 2 , 3 , 4 ) + feature ( arg1 ) . out.out").toString());
    }

    public void testNegativeConstantArgument() throws ParseException {
        assertEquals("foo(-1.2)", new RankingExpression("foo(-1.2)").toString());
    }

    public void testNaming() throws ParseException {
        RankingExpression test = new RankingExpression("a+b");
        test.setName("test");
        assertEquals("test: a + b", test.toString());
    }

    public void testCondition() throws ParseException {
        RankingExpression expression = new RankingExpression("if(1<2,3,4)");
        assertTrue(expression.getRoot() instanceof IfNode);
    }

    public void testFileImporting() throws ParseException {
        RankingExpression expression = new RankingExpression(new File("src/test/files/simple.expression"));
        assertEquals("simple: a + b", expression.toString());
    }

    public void testNonCanonicalLegalStrings() throws ParseException {
        assertParse("a * b + c * d", "a* (b) + \nc*d");
    }

    public void testEquality() throws ParseException {
        assertEquals(new RankingExpression("if ( attribute(foo)==\"BAR\",log(attribute(popularity)+5),log(fieldMatch(title).proximity)*fieldMatch(title).completeness)"),
                     new RankingExpression("if(attribute(foo)==\"BAR\",  log(attribute(popularity)+5),log(fieldMatch(title).proximity) * fieldMatch(title).completeness)"));

        assertFalse(new RankingExpression("if ( attribute(foo)==\"BAR\",log(attribute(popularity)+5),log(fieldMatch(title).proximity)*fieldMatch(title).completeness)").equals(
                    new RankingExpression("if(attribute(foo)==\"BAR\",  log(attribute(popularity)+5),log(fieldMatch(title).earliness) * fieldMatch(title).completeness)")));
    }

    public void testSetMembershipConditions() throws ParseException {
        assertEquals(new RankingExpression("if ( attribute(foo) in [\"FOO\",  \"BAR\"],log(attribute(popularity)+5),log(fieldMatch(title).proximity)*fieldMatch(title).completeness)"),
                     new RankingExpression("if(attribute(foo) in [\"FOO\",\"BAR\"],  log(attribute(popularity)+5),log(fieldMatch(title).proximity) * fieldMatch(title).completeness)"));

        assertFalse(new RankingExpression("if ( attribute(foo) in [\"FOO\",  \"BAR\"],log(attribute(popularity)+5),log(fieldMatch(title).proximity)*fieldMatch(title).completeness)").equals(
                    new RankingExpression("if(attribute(foo) in [\"FOO\",\"BAR\"],  log(attribute(popularity)+5),log(fieldMatch(title).earliness) * fieldMatch(title).completeness)")));

        assertEquals(new RankingExpression("if ( attribute(foo) in [attribute(category),  \"BAR\"],log(attribute(popularity)+5),log(fieldMatch(title).proximity)*fieldMatch(title).completeness)"),
                     new RankingExpression("if(attribute(foo) in [attribute(category),\"BAR\"],  log(attribute(popularity)+5),log(fieldMatch(title).proximity) * fieldMatch(title).completeness)"));
        assertEquals(new RankingExpression("if (GENDER$ in [-1.0, 1.0], 1, 0)"), new RankingExpression("if (GENDER$ in [-1.0, 1.0], 1, 0)"));
    }

    public void testComments() throws ParseException {
        assertEquals(new RankingExpression("if ( attribute(foo) in [\"FOO\",  \"BAR\"],\n" +
        		"# a comment\n" +
        		"log(attribute(popularity)+5),log(fieldMatch(title).proximity)*" +
        		"# a multiline \n" +
        		" # comment\n" +
        		"fieldMatch(title).completeness)"),
                new RankingExpression("if(attribute(foo) in [\"FOO\",\"BAR\"],  log(attribute(popularity)+5),log(fieldMatch(title).proximity) * fieldMatch(title).completeness)"));
    }

    public void testIsNan() throws ParseException {
        String strExpr = "if (isNan(attribute(foo)) == 1.0, 1.0, attribute(foo))";
        RankingExpression expr = new RankingExpression(strExpr);
        CompositeNode root = (CompositeNode)expr.getRoot();
        CompositeNode comparison = (CompositeNode)root.children().get(0);
        ExpressionNode isNan = comparison.children().get(0);
        assertTrue(isNan instanceof FunctionNode);
        assertEquals("isNan(attribute(foo))", isNan.toString());
    }

    protected static void assertParse(String expected, String expression) throws ParseException {
        assertEquals(expected, new RankingExpression(expression).toString());
    }

    private void assertScript(String expression, List<ExpressionFunction> macros, List<String> expectedScripts)
            throws ParseException {
        boolean print = false;
        if (print)
            System.out.println("Parsing expression '" + expression + "'.");

        RankingExpression exp = new RankingExpression(expression);
        Map<String, String> scripts = exp.getRankProperties(macros);
        if (print) {
            for (String key : scripts.keySet()) {
                System.out.println("Script '" + key + "': " + scripts.get(key));
            }
        }

        for (Map.Entry<String, String> m : scripts.entrySet())
            System.out.println(m);
        for (int i = 0; i < expectedScripts.size();) {
            String val = expectedScripts.get(i++);
            assertTrue("Script contains " + val, scripts.containsValue(val));
        }
        if (print)
            System.out.println("");
    }
}
