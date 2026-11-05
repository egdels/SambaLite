package de.schliweb.sambalite.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.hierynomus.mserref.NtStatus;
import com.hierynomus.mssmb2.SMB2MessageCommandCode;
import com.hierynomus.mssmb2.SMBApiException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.Test;

/** Tests the classification of sync errors (#50). */
public class SyncErrorCategoryTest {

  private static SMBApiException smb(NtStatus status) {
    return new SMBApiException(
        status.getValue(), SMB2MessageCommandCode.SMB2_CREATE, status.name(), null);
  }

  @Test
  public void smbStatus_mapsToCategory() {
    assertEquals(
        SyncErrorCategory.ACCESS_DENIED,
        SyncErrorCategory.classify(smb(NtStatus.STATUS_ACCESS_DENIED)));
    assertEquals(
        SyncErrorCategory.INVALID_NAME,
        SyncErrorCategory.classify(smb(NtStatus.STATUS_OBJECT_NAME_INVALID)));
    assertEquals(
        SyncErrorCategory.AUTH, SyncErrorCategory.classify(smb(NtStatus.STATUS_LOGON_FAILURE)));
    assertEquals(
        SyncErrorCategory.SHARE_NOT_FOUND,
        SyncErrorCategory.classify(smb(NtStatus.STATUS_BAD_NETWORK_NAME)));
    assertEquals(
        SyncErrorCategory.FILE_IN_USE,
        SyncErrorCategory.classify(smb(NtStatus.STATUS_SHARING_VIOLATION)));
    assertEquals(
        SyncErrorCategory.SERVER_FULL, SyncErrorCategory.classify(smb(NtStatus.STATUS_DISK_FULL)));
  }

  @Test
  public void wrappedSmbException_isFoundInTheCauseChain() {
    Exception wrapped =
        new RuntimeException("upload failed", new IOException(smb(NtStatus.STATUS_ACCESS_DENIED)));
    assertEquals(SyncErrorCategory.ACCESS_DENIED, SyncErrorCategory.classify(wrapped));
  }

  @Test
  public void statusInMessage_isRecognised() {
    Exception e = new IOException("STATUS_OBJECT_NAME_INVALID (0xc0000033): Create failed");
    assertEquals(SyncErrorCategory.INVALID_NAME, SyncErrorCategory.classify(e));
  }

  @Test
  public void networkAndLocalErrors() {
    assertEquals(
        SyncErrorCategory.NETWORK,
        SyncErrorCategory.classify(new SocketTimeoutException("timeout")));
    assertEquals(
        SyncErrorCategory.LOCAL_ACCESS,
        SyncErrorCategory.classify(new FileNotFoundException("open failed: EACCES")));
    assertEquals(
        SyncErrorCategory.LOCAL_SPACE,
        SyncErrorCategory.classify(new IOException("write failed: ENOSPC (No space left on device)")));
  }

  @Test
  public void unknownError_withInvalidName_isPutDownToTheName() {
    Exception e = new IOException("something odd");
    assertEquals(SyncErrorCategory.OTHER, SyncErrorCategory.classify(e, "fine.txt"));
    assertEquals(SyncErrorCategory.INVALID_NAME, SyncErrorCategory.classify(e, "Einkauf: Samstag.txt"));
  }

  @Test
  public void telling_status_winsOverTheName() {
    assertEquals(
        SyncErrorCategory.ACCESS_DENIED,
        SyncErrorCategory.classify(smb(NtStatus.STATUS_ACCESS_DENIED), "bad?.txt"));
  }

  @Test
  public void isInvalidSmbName() {
    assertTrue(SyncErrorCategory.isInvalidSmbName("a:b"));
    assertTrue(SyncErrorCategory.isInvalidSmbName("what?.png"));
    assertTrue(SyncErrorCategory.isInvalidSmbName("draft."));
    assertTrue(SyncErrorCategory.isInvalidSmbName("trailing "));
    assertTrue(SyncErrorCategory.isInvalidSmbName("quote\".txt"));
    assertFalse(SyncErrorCategory.isInvalidSmbName("IMG_0412.jpg"));
    assertFalse(SyncErrorCategory.isInvalidSmbName("Café (1).pdf"));
    assertFalse(SyncErrorCategory.isInvalidSmbName(""));
  }

  @Test
  public void describe_fallsBackToTheClassName() {
    assertEquals("boom", SyncErrorCategory.describe(new IOException("boom")));
    assertEquals("IOException", SyncErrorCategory.describe(new IOException()));
    assertEquals("", SyncErrorCategory.describe(null));
  }
}
