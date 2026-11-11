# FadCam integration QA review

Base: `7307cb90fe1012d77de24d651d819752fd08f0f3`.

The tests in this revision cover recording stop before a session produces output,
registered MP4 tracks with no payload, save-to-gallery copy integrity, existing
SAF document protection, and stale or equal-size gallery scan results. The
decodable writer fixture is synthetic FFmpeg-generated color and tone. It
contains no device or user media.

Review the product diff with its corresponding test class. For recordings, check
the reported completion against registered track payload and finalized output.
For storage, check that source removal requires an independently verified new
copy and that an existing returned document is never opened for write. For
gallery refresh, check that the current request owns the update even if counts
match.

Physical camera, USB hardware, platform SAF behavior, GL pixels, and device
performance have not been tested here. A device run requires separate approval.
Use the preservation rules in AGENTS.md.
