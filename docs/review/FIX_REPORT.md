# 로컬 결함 수정 및 검증

검수 시작 커밋은 `4283201eee8cb1c0f9f9f52191e6dace5ce777e0`이다.
작업 전 Git 상태는 깨끗했다. 과거 `validation.json`은 이번 결과로 덮어쓰지 않았다.

| 항목 | 발생 조건과 변경 경로 | 검증 근거와 한계 |
|---|---|---|
| A1 | `TrashManager.moveToTrash`에서 rename 후 목록 저장이 실패하면 유일한 사본을 삭제했다. 닫기와 크기 확인을 삭제보다 앞으로 옮기고, AtomicFile과 이동·복원 진행 기록을 추가했다. | 원본 고정 바이트가 사라지는 실패를 수정 전 재현. 목록 저장 장애, 원위치 충돌, write/flush/close 실패 시험. 실제 SAF 제공자 및 프로세스 강제 종료는 미실행. |
| A2 | `GLRecordingPipeline.rolloverSegment`의 이전 출력이 다음 파일 준비 실패 뒤 남을 수 있었다. 다음 출력은 경로 또는 FD+URI와 조각 번호를 함께 전달하고 이전 대상을 재사용하지 않는다. | `RecordingOutputOwnershipTest`의 출력 계약 시험. 실제 SAF 분할 및 A/B/C 저장은 미실행. |
| A3 | `FragmentedMp4MuxerWrapper`는 완전히 기록된 경계에서만 복구하고 동일 출력의 위치 복원이 안 되면 실패를 전달한다. 최종화 버퍼는 완전히 쓰고 진행 없음은 제한한다. | `FragmentedMp4WriteRecoveryTest`의 부분 쓰기 및 채널 실패 주입. 실녹화 MP4는 생성하지 않았다. |
| A4 | `RecordsDeletionService`의 임의 SAF 내보내기는 닫기·바이트 확인 후 성공 처리한다. 실패한 새 사본만 정리하며 잔류 URI를 기록한다. 대상 tree가 다른 작업을 합치지 않는다. 요청 ID와 실행 중 항목을 저장하고 취소·미처리를 구분한다. | `RecordsDeletionSessionTransactionTest`의 다른 tree, claim 재로딩, 중복 요청 기록, 조기 EOF·null·close·취소 시험. 중단 후 제공자가 실제 성공 여부를 입증하지 못한 경우 실패로 표시하고 자동 재실행하지 않는다. 모든 제공자의 중단 시점별 복구는 미검증. |
| B1 | `RecordingService` 시작 경로는 이전 정리 중 새 세션을 받지 않는다. GL drain 소유자가 반환한 뒤 종료 처리하며 듀얼 종료도 UI 스레드에서 기다리지 않는다. | 실물 코덱이 응답하지 않는 조건과 기존 출력 파일 해시의 종단 검증은 미실행. |
| B2 | 미리보기에서 기록용 muxer 생성을 지연하고 미시작 writer도 닫는다. | 실제 writer 해제 및 미시작/중복 호출의 로컬 결과는 아래 XML 집계 참조. 기기 FD·GL 자원 수명은 미검증. |
| B3 | `RecordingPermissions`를 본화면·바로촬영·서비스에서 사용한다. 무음은 카메라만, 유음은 카메라와 마이크가 필요하다. | 달력 진입의 실제 Intent 경로, 무음/유음, 활성 세션 중지, 기존 설정 유지 시험. |
| B4 | `PersonalHomeFragment` 사진 버튼을 PAUSED에서 비활성화하고 싱글/듀얼 서비스의 외부 사진 명령도 거절한다. | 기존 촬영 명령 테스트를 유지하고 PAUSED에 비활성·무전송·안내 단언을 추가했다. 실기기 촬영은 미실행. |
| B5 | `GLRecordingPipeline.capturePhotoFrame`를 GL worker에 예약하고 `GLWatermarkRenderer.capturePhotoFrameOffscreen`의 별도 FBO에서 읽는다. 사진 요청이 영상 encoder swap, PTS, 전역 워터마크 상태를 변경하지 않는다. | `PhotoOffscreenOwnershipTest`에서 모의 GL 경계와 반복/실패/종료 콜백을 확인한다. 실제 픽셀·동영상 타임스탬프는 미검증. |
| C1 | `SeekableFragmentedMp4MediaSourceFactory`에서 볼륨을 추정한 파일 경로 대신 원래 content URI를 스캔·재생에 사용한다. 인덱스 FD와 재생 FD를 분리한다. | 동명 내부/SD 파일과 원래 URI, 별도 PFD를 시험했다. 실물 이동식 저장소는 미검증. |
| C2 | `PlayerHolder`에서 제한된 worker로 준비하고 현재 요청·소유자가 일치할 때 applicationLooper에서 적용한다. | 느린 A보다 빠른 B가 먼저 적용되고 A가 덮어쓰지 않는 순서 시험. 실물 제공자 지연은 미측정. |
| C3 | `RecordsAdapter`에서 Glide ImageView 요청을 교체·취소하고 디스크 저장을 Glide에 위임한다. 전체 URI와 버전으로 서명한다. | 충돌 URI와 버전 변경 시험. 기존 해시 캐시는 새 파일로 읽지 않는다. 모든 재활용 콜백 순서의 실기기 재현은 미실행. |
| C4 | `FastFileScanner`가 불완전한 조회를 보고하고 `VideoIndexRepository`는 성공한 범위에서만 누락을 제거한다. | 조회 실패와 정상 빈 폴더, A만 보이는 부분 조회에서 B 보존 시험. |
| D1 | `UsbCameraActivity.connect/selectMode`: 시작 실패 후 장치가 제공한 모드 목록을 유지한다. 사용자가 다른 모드를 고르면 재시도하고, 성공 시 협상 모드를 표시한다. | 실패→다른 모드→첫 프레임의 모의 백엔드 검증. 실제 스트림 미실행. |
| D2 | `startConnection/canContinue/handleUsbPermission`: 기기·권한 요청·Surface·세대를 고정하고 각 블로킹 호출이 끝날 때 유효성을 다시 확인한다. | 늦은 권한 결과 RED 재현, 연결 단계별 취소 및 권한 검증. 실제 시스템 USB 권한 창 미실행. |
| D3 | `onSurfaceTextureDestroyed/finishWorker`: 네이티브→Surface→인수한 Texture→worker 순서로 종료하며 큐 거절도 처리한다. | 종료 순서·큐 거절 시험. 실제 네이티브 해제 시간 미측정. |
| D4 | `queueFocus/completeFocus`: 가장 최근 입력을 다음 명령으로 직렬화한다. 이전 세대 결과는 UI를 덮을 수 없다. | 마지막 AF가 지연 MF를 덮지 못한 기존 실패 RED 재현. AF→MF·실패·세션 전환 시험. 실제 초점 모터 미실행. |
| E1 | `settings.gradle.kts`가 잠금과 다른 Media3, dirty 사본 및 누락을 실패 처리한다. 네이티브 검사에서 arm64 UVC 필수 라이브러리, ELF/ZIP 정렬, 빈 APK를 확인한다. | Python fixture 2개 통과. 실제 APK의 정보는 최종 실행 기록 참조. |
| E2 | 갤러리 선택 수, 미니 앱, USB 기본 장치와 오류/취소 메시지를 리소스로 연결했다. | 로컬 리소스 컴파일. 접근성의 실물 사용은 미검증. |
| E3 | 성능 개선율을 추정하지 않았다. USB 첫 화면·사진 저장 지연, 작은 가로창과 큰 글씨, 로그 비용의 실측은 미완료다. | 성능·화질·FPS 수치를 보고하지 않는다. |

기존 패키지/네임스페이스, SDK, 설치 대상 user 0, Media3·AAR 잠금, 바로촬영 설정과 저장 정책을 유지했다. 갤러리의 스와이프와 확장 타임라인은 기존 분리 계약을 유지했다. USB 영상 녹화는 추가하지 않았다.

실물 시험은 별도 승인 후 수행한다. APK 해시, Android 버전, USB VID/PID와 OTG 구성을 기록하고 움직이는 대상/렌즈 가림으로 실제 영상인지 확인한다. 30초 미리보기, 사진 3장, 다른 모드의 협상 결과, 지원되는 AF/MF, 분리·재연결 및 화면 재진입 각각 3회, 내장 카메라와 저장 설정 복귀를 확인한다. 녹화 시험에서는 FFmpeg 전체 디코딩, ffprobe 트랙/길이, `verify_recording_mp4.py`의 stsz/trun 대조를 별도로 실행한다.

## 실행 결과

- 전체 JVM 새 실행: True; 통과; 311개, 성공 311, 실패 0, 오류 0, 건너뜀 0.
- 명령: `gradlew.bat :app:testDefaultDebugUnitTest :app:assembleDefaultDebug :app:fadcamFixBuildInfo --offline --no-configuration-cache --no-daemon --max-workers=2 --console=plain`. build-info 출력용 임시 init 스크립트와 격리 Gradle 캐시를 사용했다. composite 조회는 `gradlew.bat :app:dependencies --configuration defaultDebugRuntimeClasspath --offline`로 별도 실행했다.
- 정렬 도구: `python tools/test_native_alignment.py` 2개 통과.
- 환경: JDK 17.0.20.1, Gradle 8.13, Android Build Tools 35.0.0, AGP 8.13.1. Media3 composite 선택: {'muxer': True, 'common': True, 'container': True}.
- 추적 diff SHA-256: `ab3b2d04e12ce9535e40e574844c47b060f4166b1c70a98927778c2cee3d290c`. 미추적 파일을 포함한 소스 변경 목록 SHA-256: `ab681bc73e88027a8f5f499004905dbdfea51392b0df2a8cb24ca9d036194770`. 검증 시작/종료 소스 일치.
- APK: `FadCam-fixes-120f535043a6.apk`, 83,164,983바이트, SHA-256 `120f535043a6fc65e64d5b850c4d5a2a7f7b7b2570c6a590c26d9c1f42e16a71`. com.pyeonje.fadcam.beta, 4.0.0-beta10.6 (52), arm64-v8a, 네이티브 14개 ELF/ZIP 16KiB 확인.
- 이번 실행의 원본 XML별 결과, 시험/빌드 로그 해시, 모든 변경 입력 파일은 validation-fixes.json에 있다.
