# FadCam 독립 결함 검수

판정은 이번 로컬 검수에서 증명한 범위다. **PARTIAL**은 수정이 존재해도 요청한 모든 실패 조건 또는 실제 자원 수명이 입증되지 않았음을 뜻한다. 시험 이름은 실제 XML의 클래스 또는 사례와 연결된다.

| 항목 ID | 현재 코드 근거 | 시험 근거 | 판정 | 남은 위험 |
|---|---|---|---|---|
| A1 | 기존 수정 있음. `TrashManager.moveToTrash/copyClosed/recordRestore`가 닫기·크기 검증과 이동/복원 기록을 먼저 수행한다. 이번 `restoreItemsFromTrash/restoreItemsToSaf`는 원본을 찾지 못해도 항목을 삭제하거나 성공 처리하지 않는다. `permanentlyDeleteItems/emptyAllTrash` 및 자동 삭제는 물리 삭제 실패 시 항목을 유지한다. | `TrashManagerTransactionTest`가 실제 임시 파일·목록을 사용한다. 목록 장애/롤백 충돌의 바이트 보존, 스트림 write/flush/close 예외, 원본 불명 시 목록과 실패 결과, 삭제 실패 시 파일과 목록 유지. | PARTIAL | SAF의 실제 close, 강제 종료와 저장소 분리의 전체 조합은 미검증. |
| A2 | 기존 수정 있음. `GLRecordingPipeline.rolloverSegment/setNextOutput`는 완료된 출력 참조를 비우고, 다음 조각에 새 경로 또는 FD+URI가 있어야 진행한다. `RecordingService.GLSegmentCallback`은 같은 SAF URI를 열기 전 거절하고 비절단 모드로 새 FD를 확인한다. | `RecordingOutputOwnershipTest`, `RecordingSafSegmentGuardTest`: 실제 분기/가드, 모의 muxer·FD. 이전 URI의 open 호출 0, stop 후 B 준비 거절. | PARTIAL | 실제 A/B/C FD, 동일 물리 파일 alias, 실패 전후 A 파일 SHA의 기기 종단 검증 없음. |
| A3 | `FragmentedMp4MuxerWrapper.writeSegmentWithRecovery/finalizeWithChannel/stop`가 같은 파일의 확정 경계에서 복원한다. 이번에는 디스크 출력의 완전 fragment 누락을 성공 처리하지 않고 실제 최종화 경로에 실패를 주입했다. | `FragmentedMp4WriteRecoveryTest`가 임시 파일, 짧은 FileChannel, 최종화 moov/mvhd/mdat 실패 및 롤백 후 재시도를 호출한다. | PARTIAL | 실녹화, 비 seek SAF 제공자, 서비스 알림까지의 실제 코덱 오류는 미검증. |
| A4 | 기존 수정 있음. `RecordsDeletionService.processExportItemAsync/copyExportPayload`: 닫기·복사 크기 확인 후 성공, 실패 사본은 finally에서 정리하고 잔류 URI 기록. `accepts/claimNext/reconcileInterruptedClaim`이 tree 분리, 저장한 claim, 확인 불가 작업의 재실행 방지를 수행. 이번 `onStartCommand/acknowledgeCompletion`은 활성 작업을 지우거나 중단하는 오래된 완료 알림을 무시한다. | `RecordsDeletionSessionTransactionTest`: 실제 서비스 Intent와 저장소의 inFlight 보존, 실제 helper에 close·null·취소 주입, 다른 tree 거절과 재로딩. 조기 EOF의 크기 판정은 helper 시험이다. | PARTIAL | provider의 실제 부분 파일 삭제/삭제 실패, 원본 삭제 직후 프로세스 중단의 완전한 종단 시험 없음. |
| B1 | `RecordingService` 시작 및 stop gate가 정리 소유권을 유지한다. 이번 `GLRecordingPipeline.renderBlackFrame`의 별도 drain 제거, 오디오 시작 fallback을 drain 소유자로 이동해 코덱/muxer의 중복 접근 경로를 닫았다. | `RecordingOutputOwnershipTest`: 출력 소유권과 black-frame 큐의 별도 dequeue 차단. 기존 `RecordingToggleActivityTest`가 active/starting/finalizing 상태와 바로촬영 진입을 확인한다. | PARTIAL | 실제 drain이 블록된 채 STOP→START를 거치는 전체 서비스·FD 시험 없음. |
| B2 | `GLRecordingPipeline.prepareSurfaces/quickReleaseForPreviewOnly`는 미리보기에서 muxer를 열지 않거나 있으면 닫는다. `FragmentedMp4MuxerWrapper.release`는 미시작 writer도 닫는다. | `RecordingOutputOwnershipTest.servicePreviewReleaseClosesActualUnstartedWriterAndDescriptor`가 서비스 해제 경로에서 실제 patched writer와 FD를 확인한다. `FragmentedMp4WriteRecoveryTest`는 wrapper 해제를 직접 호출한다. | PASS | 실제 장치에서 GL 스레드·코덱·FD 해제가 반복 재진입 후 누수 없는지 미검증. |
| B3 | `RecordingPermissions.canRecord`를 `RecordingStartActivity`, `PersonalHomeFragment`, `RecordingService.startRecording`에서 공유한다. | `RecordingToggleActivityTest.calendarShortcutStartsOnlySilentVideoWithMicrophoneDenied`, `RecordingSavedSettingsTest`, `SilentRecordingPermissionTest`: 실제 launcher/service 진입과 권한·저장 설정 검사. | PASS | Android 시스템의 실제 FGS 진입은 기기 범위. |
| B4 | `PersonalHomeFragment`에서 PAUSED 버튼을 비활성화하고 싱글/듀얼 서비스의 외부 사진 명령을 거절한다. | `PersonalHomePreviewTest.activeRecentButtonDispatchesExistingPhotoActionWithoutStoppingOrOpeningViewer`, `SilentRecordingPermissionTest.pausedServiceDoesNotRequestSnapshotOrChangeSession`이 실제 클릭/싱글 서비스 가드를 실행한다. | PASS | 실제 사진 저장과 듀얼 코덱은 미실행. |
| B5 | `GLRecordingPipeline.capturePhotoFrame`가 별도 `GLWatermarkRenderer.capturePhotoFrameOffscreen` 경계를 사용한다. 사진 자체가 encoder swap·PTS·전역 워터마크 값을 수정하지 않는다. | `PhotoOffscreenOwnershipTest`: 제품 예약/콜백 코드와 모의 GL readback. | PARTIAL | GPU 픽셀, 색/방향, 실제 영상 타임스탬프 미검증. |
| C1 | `SeekableFragmentedMp4MediaSourceFactory.createContentUriMediaSource/scanContentIndex`: 볼륨 경로 추정을 없애고 원래 URI로 scan·재생, 각각 별도 PFD. | `GallerySourcePreparationTest.sameNameAcrossVolumesStillReadsSelectedProviderThroughSeparatePlaybackDescriptor`: Robolectric ContentProvider 및 Media3 DataSource 실제 호출, 선택된 SD 바이트/두 FD 확인. | PARTIAL | 실제 이동식 볼륨, 원격 provider fallback 전체는 미검증. |
| C2 | `PlayerHolder`의 제한 worker가 판별·인덱스를 준비하고 현재 세대/플레이어 일치 시 applicationLooper에서 반영한다. `VideoPlayerActivity`의 duration 조회는 worker 사용. | `GallerySourcePreparationTest`가 제어된 A/B 순서, 최신 요청 반영과 종료 뒤 반영 0을 제품 준비·적용 경로에서 검사한다. | PASS | 실제 provider 지연/프레임 성능 수치는 없음. |
| C3 | `RecordsAdapter.setThumbnail`과 재바인딩/재활용이 같은 ImageView의 Glide 요청을 교체하고, 전체 URI+버전 서명으로 캐시를 분리한다. | `GalleryThumbnailSignatureTest`: 실제 Glide 디스크의 Aa/BB 및 버전 분리, 실제 holder A→B 후 지연 A가 B 픽셀을 덮지 못함. | PASS | 실제 기기에서 빠른 스크롤과 메모리 압박 미측정. |
| C4 | `FastFileScanner`가 정상/부분 스캔을 구별하고 `VideoIndexRepository`는 확인한 범위에서만 제거한다. 이번에는 상위 SAF tree 안에서 FadCam 하위 루트만 읽은 경우 형제 인덱스를 보존하도록 보완했다. | `VideoIndexIncompleteScanTest`: 실제 scanner와 모의 resolver/DAO로 null/loading·완전 스캔, 하위 범위의 확인된 삭제 및 범위 밖 보존을 확인. | PARTIAL | opaque 문서 ID에는 삭제 근거가 없으면 보존한다. 실제 SD 분리·복귀 미실행. |
| D1 | `UsbCameraActivity.connect/selectMode`가 목록과 스트림 시작을 분리한다. 이번에는 같은 SurfaceTexture에서 늦은 레이아웃 콜백과 실제 새 프레임을 구별하도록 보완했다. | `UsbCameraLifecycleTest`: 모의 엔진 모드 실패/협상, 첫 프레임, 재사용 texture의 타임스탬프. | PARTIAL | 실제 USB 협상 모드·5MP·픽셀 미검증. |
| D2 | `startConnection/canContinue/handleUsbPermission`: 불변 기기/세대/모드/Surface와 요청 ID/시스템 허가 검증. | `UsbCameraIntegrationTest.obsoleteUsbPermissionResultDoesNotReplaceCurrentDeviceStatus`와 Lifecycle의 open/modes/start 취소. 모의 시스템 권한. | PASS | 실물 권한 창/기기 분리 미실행. |
| D3 | `onSurfaceTextureDestroyed/finishWorker`: 앱이 인수한 texture는 native→Surface 다음 해제, worker 종료 후 callback은 TextureView 소유. | `UsbCameraLifecycleTest`가 양쪽 콜백 순서, 실행 중 취소, 큐 거절 및 자원별 해제 순서/횟수를 제품 수명 경로에서 검사한다. | PASS | 실제 JNI 블로킹과 OS GL 자원은 모의 시험으로 검증되지 않음. |
| D4 | `queueFocus/completeFocus`가 작업 세대와 최신 의도를 따르고 지연 MF를 AF로 대체한다. | `UsbCameraIntegrationTest.lastAutoFocusCancelsManualWriteWaitingBehindInFlightManual`와 Lifecycle 반대 순서/실패/재연결. | PASS | 실제 초점 모터 미실행. |
| E1 | `settings.gradle.kts`의 고정 Media3 Git 경로/커밋·clean 검증과 세 모듈 치환, AAR SHA 검사, `tools/check_native_alignment.py`를 확인했다. | 실제 Git·Gradle 그래프·APK 자체·서명·ZIP·ELF·AAR 바이트 대조. Python fixture 2개. | PASS | 기기 설치 및 실제로 패치가 내는 MP4 재생 품질은 별도. |
| E2 | `RecordsFragment`/locale 리소스, `UsbCameraActivity`의 지원 상태/접근성 및 파일 오류 기본 문구 확인. 이번 작업은 내보내기 예외 기본 문구도 기존 한국어 리소스로 연결. | APK 리소스 빌드, USB 상태 테스트 및 PAUSED 접근성 단언. | PARTIAL | 모든 시스템 언어·TalkBack 조합 미실행. |
| E3 | 실기기 성능·레이아웃 개선 판단에 필요한 측정값 없음. | 제한 worker/지연 적용은 C2 JVM 검증으로만 구분했다. | NOT_RUN | USB 시작/사진 저장 지연, 큰 글꼴 및 실제 GL 성능은 미측정. |


## 입력과 소유 범위

- HEAD: `4283201eee8cb1c0f9f9f52191e6dace5ce777e0`. 시작 시 수정 입력의 파일별 해시 및 추적 diff가 이전 `validation-fixes.json`과 모두 일치했다. 별도 사용자 변경은 확인되지 않았다. 기준 커밋과의 차이에는 앞선 수정과 이번 보완이 모두 포함된다.
- 시작 diff SHA-256: `ab3b2d04e12ce9535e40e574844c47b060f4166b1c70a98927778c2cee3d290c`; 최종 diff SHA-256: `ad687b6454e9cae39dcebd4337384cb380eb4e769b252548db9011bcbdd4d1dc`; 미추적 소스를 포함한 최종 입력 목록 SHA-256: `4db786f31c159cdbe2b9ad9adab96fdc3a1c7d0ece38dcfbc6613ea0b4d79b53`. 실행 전후 동일.
- 새로 달라진 파일: 16개. 아래는 직전 311개 실행과 비교한 목록이다.

- `app/src/main/java/com/fadcam/data/FastFileScanner.java`
- `app/src/main/java/com/fadcam/media/FragmentedMp4MuxerWrapper.java`
- `app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java`
- `app/src/main/java/com/fadcam/service/RecordsDeletionService.java`
- `app/src/main/java/com/fadcam/ui/UsbCameraActivity.java`
- `app/src/main/java/com/fadcam/utils/TrashManager.java`
- `app/src/test/java/com/fadcam/data/VideoIndexIncompleteScanTest.java`
- `app/src/test/java/com/fadcam/media/FragmentedMp4WriteRecoveryTest.java`
- `app/src/test/java/com/fadcam/opengl/PhotoOffscreenOwnershipTest.java`
- `app/src/test/java/com/fadcam/opengl/RecordingOutputOwnershipTest.java`
- `app/src/test/java/com/fadcam/playback/GallerySourcePreparationTest.java`
- `app/src/test/java/com/fadcam/service/RecordsDeletionSessionTransactionTest.java`
- `app/src/test/java/com/fadcam/ui/GalleryThumbnailSignatureTest.java`
- `app/src/test/java/com/fadcam/ui/UsbCameraIntegrationTest.java`
- `app/src/test/java/com/fadcam/ui/UsbCameraLifecycleTest.java`
- `app/src/test/java/com/fadcam/utils/TrashManagerTransactionTest.java`

## 실제 검증

- 이전 결과: XML 55개, 시험 311개, 실패·오류·건너뜀·중복 0개. XML 개별 해시까지 기존 기록과 일치했다. 시각 시험의 상속을 제거했지만 원래 시험은 `PersonalHomePreviewTest`에서 실행되며, PAUSED 단언 변경은 새 정책을 명시한다.
- 최종 결과: **326개 통과** / 실패 0 / 오류 0 / 건너뜀 0, 55개 suite. 테스트 이름의 중복과 `@Test`가 있는 미보고 클래스 없음. 기기용 androidTest는 JVM 모수와 별도다.
- 실패 재현: 이전 완료 Intent가 활성 내보내기 서비스를 멈추는 A4 시험, 같은 프레임에 대한 레이아웃 callback이 사진 버튼을 켜는 D1 시험에서 각각 수정 전 실패를 확인했다. A1의 신규 시험은 초기 코드에서 실패할 조건을 코드로 확인했지만, 기록된 첫 실행은 수정 입력이 반영되어 RED로 계산하지 않았다.
- APK: `FadCam-independent-fbb7e1678b50.apk`, 84,057,636바이트, SHA-256 `fbb7e1678b50b7be74821b8ede7470ce947378b36c0488e04ff97b627df82c0f`. `com.pyeonje.fadcam.beta`, 4.0.0-beta10.6 (52), arm64-v8a. 서명 SHA-256 `c9da6360eab3ca46d9b7959646a3bcd61c4e8bea933354819cf2b3d8bad9fea6`; 직전 APK와 같은 서명이다. APK의 14개 네이티브 라이브러리 ELF·ZIP 16 KiB 통과.
- JDK 17.0.20.1, Gradle 8.13, Build Tools 35.0.0. 패치 Media3 `51c6a4f96d4dc08d421682d96ecc5a044ea1decb`의 clean 체크아웃과 세 composite 치환을 확인했다. Droid UVC AAR SHA-256 `a44a5dcb2be88318ebf4032e90e484192d3976eb19e299d2b516ae7d70fb1da0`; APK에 패키징된 JNI 바이트가 AAR과 동일하다.
- 실행: Gradle wrapper의 `:app:testDefaultDebugUnitTest :app:assembleDefaultDebug` 및 별도 `:app:dependencies --configuration defaultDebugRuntimeClasspath`, 실제 SDK의 apksigner·aapt·zipalign 및 ELF 검사. 기기 설치·실행, 실녹화/USB 및 원격 push 없음.

이전 보고에서 더 엄격히 제한한 범위: A2/A3/B1은 저장 실패의 기기 종단 검증을 포함하지 않는다. A4의 stream 실패는 helper를 통과하며 실제 SAF 제공자의 부분 사본 삭제까지 재현하지 않았다. B2의 실제 writer/FD JVM 증거는 GPU·카메라 자원까지 보증하지 않는다. 새 실물 시험은 USB 실제 프레임·5MP·초점, 분할 SAF/MP4 ffmpeg·stsz/trun, FBO 픽셀·PTS, 저장소 분리/복구와 성능을 별도로 확인해야 한다.
