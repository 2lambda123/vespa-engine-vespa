// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.abicheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yahoo.abicheck.collector.Util;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;

import java.util.List;

public class AccessConversionTest {

  @Test
  public void testClassFlags() {
    // ACC_SUPER should be ignored
    assertEquals(
        List.of("public", "abstract"),
        Util.convertAccess(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_ABSTRACT,
            Util.classFlags));
  }

  @Test
  public void testMethodFlags() {
    // ACC_DEPRECATED should be ignored
    assertEquals(
        List.of("protected", "varargs"),
        Util.convertAccess(
            Opcodes.ACC_PROTECTED | Opcodes.ACC_VARARGS | Opcodes.ACC_DEPRECATED,
            Util.methodFlags));
  }

  @Test
  public void testFieldFlags() {
    assertEquals(
        List.of("private", "volatile"),
        Util.convertAccess(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_VOLATILE,
            Util.fieldFlags));
  }

  @Test
  public void testUnsupportedFlags() {
    // ACC_MODULE is not a valid flag for fields
    assertThrows(IllegalArgumentException.class,
        () -> Util.convertAccess(Opcodes.ACC_MODULE, Util.fieldFlags));
  }
}
