package de.schliweb.sambalite.service;

import static org.junit.Assert.*;

import org.junit.Test;

/** Unit tests for {@link SmbBackgroundService.OpCtx}. */
public class OpCtxTest {

  @Test
  public void constructor_setsName() {
    SmbBackgroundService.OpCtx ctx = new SmbBackgroundService.OpCtx("upload1");
    assertEquals("upload1", ctx.name);
  }

  @Test
  public void cancelled_defaultIsFalse() {
    SmbBackgroundService.OpCtx ctx = new SmbBackgroundService.OpCtx("op");
    assertFalse(ctx.cancelled.get());
  }

  @Test
  public void cancelled_canBeSetToTrue() {
    SmbBackgroundService.OpCtx ctx = new SmbBackgroundService.OpCtx("op");
    ctx.cancelled.set(true);
    assertTrue(ctx.cancelled.get());
  }

  @Test
  public void future_defaultIsNull() {
    SmbBackgroundService.OpCtx ctx = new SmbBackgroundService.OpCtx("op");
    assertNull(ctx.future);
  }

  @Test
  public void inactivityTask_defaultIsNull() {
    SmbBackgroundService.OpCtx ctx = new SmbBackgroundService.OpCtx("op");
    assertNull(ctx.inactivityTask);
  }

  @Test
  public void absoluteTimeoutTask_defaultIsNull() {
    SmbBackgroundService.OpCtx ctx = new SmbBackgroundService.OpCtx("op");
    assertNull(ctx.absoluteTimeoutTask);
  }
}
