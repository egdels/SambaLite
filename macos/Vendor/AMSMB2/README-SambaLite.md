# AMSMB2, vendored

AMSMB2 4.0.3 (https://github.com/amosavian/AMSMB2, commit 1726aaa) with
libsmb2 at the commit that release pins, LGPL-2.1-or-later. Only what
the macOS build needs is here: `AMSMB2/`, `Dependencies/libsmb2/{lib,include}`
and the licence files. Built as a dynamic library, shipped in
`SambaLite.app/Contents/Frameworks`, so it can be replaced.

## Patch

`AMSMB2/Context.swift`, `wait_for_reply`: on a timeout the libsmb2 context
is destroyed before ETIMEDOUT is thrown. Without it, the timed-out request
stays queued with a pointer to a stack frame that is gone; a late reply
(a NAS that wakes up, a paused server that resumes) is then delivered
through that pointer the next time the context is serviced, which crashed
the app (SIGSEGV in `SMB2Client.generic_handler`, seen when a server came
back after a 30 s stall during a download, with SMB3 encryption on).

Upstream tried this in 2020 and reverted it (a5954d3, "breaks context
after timeout"): the client is unusable after a timeout. SambaLite does not
mind, because `SMBFileSystem` builds a new session after any connection
error. The proper fix is upstream PR #138
(https://github.com/amosavian/AMSMB2/pull/138, open since 2026-08). It
moves each request's state to the heap, so an abandoned request can no
longer write into freed memory. Remove the vendored copy once a release
contains it, after `SAMBALITE_SMB_STALL_TEST=1` passes against that
release.

`Package.swift`: no test target, hence no swift-atomics dependency.
