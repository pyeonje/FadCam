# FadCam 개인 포크: 카메라·갤러리·USB 검수 시작점

이 브랜치는 Android용 FadCam 개인 포크의 삼성형 카메라·갤러리 조작과 앱 내부 USB 내시경 지원을 검토하기 위한 작업본이다. `master`는 상류 기준이고, 비교할 개인 포크의 직전 원격 기준은 [`21a6aa0428c352ff88d8479afaef7dbc734ab753`](https://github.com/pyeonje/FadCam/commit/21a6aa0428c352ff88d8479afaef7dbc734ab753)이다. 현재 작업 브랜치는 `codex/samsung-parity-windows`다. 이 문서와 같은 커밋에서 링크된 코드를 읽는다.

## 목표와 결정

- 달력 아이콘, 저장된 설정으로 바로촬영/중지, 한국어 UI, 기존 녹화 파일과 설정을 유지한다. `com.pyeonje.fadcam.beta`와 `com.fadcam` Java 네임스페이스는 그대로다.
- 사진·동영상·프로 동영상 모드, 녹화 중 일시정지/재개/중지를 제공한다. 수동 초점의 `0.00–1.00`은 줌이 아니다. 눈금을 움직이면 MF로 전환하며, AF 중앙/멀티는 Camera2에서 지원하는 영역 수에 맞춘다. 렌즈 강조는 공통 선택 표시 하나가 이동한다.
- 갤러리에서 화면을 좌우로 밀면 다른 미디어로 이동한다. 확장된 하단 타임라인만 영상 시간을 탐색한다. 다른 미디어는 0초에서 시작하고, 같은 항목의 회전은 상태를 보존한다. 앱 미리보기는 유지하고 삼성 플레이어/편집기를 호출하는 경로를 별도로 둔다.
- 내시경은 별도 앱 없이 FadCam 내부에서 선택하고, 사용자에게 카메라·USB 권한을 요청한다. USB 장치가 제공하는 모드와 초점만 활성화하며 디지털 확대와 프리뷰 JPEG 저장을 지원한다. **USB 영상 녹화는 구현하지 않았다.** 데스크톱 프로그램 개조는 이 저장소의 승인된 범위가 아니다.

## 소스 안내

| 범위 | 먼저 읽을 파일 |
| --- | --- |
| 앱 상태, 촬영 모드, 녹화/초점 요청 | [PersonalHomeFragment](../../app/src/main/java/com/fadcam/ui/PersonalHomeFragment.java), [RecordingService](../../app/src/main/java/com/fadcam/services/RecordingService.java), [GLRecordingPipeline](../../app/src/main/java/com/fadcam/opengl/GLRecordingPipeline.java) |
| MF 눈금, 중앙/멀티 AF, 렌즈 UI | [PersonalProControls](../../app/src/main/java/com/fadcam/ui/PersonalProControls.java), [PersonalCameraRuler](../../app/src/main/java/com/fadcam/ui/PersonalCameraRuler.java), [PersonalProSettings](../../app/src/main/java/com/fadcam/utils/PersonalProSettings.java), [PersonalLensSelector](../../app/src/main/java/com/fadcam/ui/PersonalLensSelector.java), [CameraLensCatalog](../../app/src/main/java/com/fadcam/utils/CameraLensCatalog.java) |
| 갤러리 그리드, 탐색, 사진/영상, 시스템 앱 연결 | [RecordsFragment](../../app/src/main/java/com/fadcam/ui/RecordsFragment.java), [ImageViewerActivity](../../app/src/main/java/com/fadcam/ui/ImageViewerActivity.java), [VideoPlayerActivity](../../app/src/main/java/com/fadcam/ui/VideoPlayerActivity.java), [GalleryExternalApps](../../app/src/main/java/com/fadcam/ui/GalleryExternalApps.java), [GalleryMediaPublisher](../../app/src/main/java/com/fadcam/service/GalleryMediaPublisher.java) |
| USB 선택, 권한, 프레임, 초점 | [UsbCameraActivity](../../app/src/main/java/com/fadcam/ui/UsbCameraActivity.java), [UsbCameraBackend](../../app/src/main/java/com/fadcam/ui/UsbCameraBackend.java), [USB 기기 테스트](../../app/src/androidTest/java/com/fadcam/ui/UsbCameraSmokeTest.java) |
| 재현용 테스트 | [JVM/Robolectric 테스트](../../app/src/test/java/com/fadcam/ui/), [Android 기기 테스트](../../app/src/androidTest/java/com/fadcam/ui/), [MP4 검사 도구](../../tools/verify_recording_mp4.py) |

## 근거와 한계

| 기준 | 확인된 범위 | 한계 |
| --- | --- | --- |
| 2026-11-12 공개 검수본 | 과거 최종 작업 소스를 토대로 갤러리 벡터 10개를 자체 도형으로 교체하고, 초점 시각 테스트의 PC 고정 경로를 임시 디렉터리로 바꿨다. 변경 자산의 크기·기존 리소스 ID와 호출 동작은 유지했다. AAPT2 리소스 컴파일/링크 10개와 초점·갤러리 JVM 검사 32개가 통과했다. | 기존 11월 2일 APK는 이 공개 커밋에서 빌드한 것이 아니다. 당시 캡처로 새 갤러리 아이콘을 검증할 수 없다. |
| 2026-11-02 USB/초점/렌즈 후보 | 여러 JVM/Robolectric 실행에서 고유 109사례 통과. 실제 ARM64 AVD에서 JNI 로딩, 잘못된 FD 거절, USB 미연결 대기 화면 확인. 삼성 Camera `16.0.00.16`을 REA/JADX로 정적 조사했다. | 109개 전체를 최종 소스에서 일괄 재실행한 결과가 아니다. 실제 구매 내시경의 영상·5MP/FPS·초점 모터, 실제 S24 Ultra AF 감각, 삼성 UI/ISP 동일성은 미검증이다. |
| 2026-10-31 카메라·갤러리 후보 | 시험 기기에서 녹화→일시정지→재개→저장, 사진/영상 양방향 이동, 다음 영상 0초 시작 확인. 시험 MP4 전체 디코딩·양쪽 MP4 샘플 테이블 비교 통과. 같은 날 휴대폰 설치본의 해시를 확인했다. | 이후 USB 후보 설치 이력과 구분한다. 현재 휴대폰 APK와 실행 상태는 확인하지 않았다. |

과거 소스 매니페스트, 빌드·설치 SHA, 화면의 해시 및 이번 공개본 확인 범위는 [validation.json](validation.json)에 있다. 원래 단위 테스트 XML, 실제 기기 로그, 원본 참조 APK·미디어는 공개하지 않았다. JSON의 과거 테스트 요약은 원본 로그 전체의 독립 재검증을 대신하지 않는다.

| 화면 증거 | 출처 |
| --- | --- |
| [USB 장치 연결 대기](evidence/usb-no-device.png) | 2026-11-02 최종 후보 실제 ARM64 AVD 화면. 연결된 내시경 없음. |
| [중앙 AF](evidence/center-af.png) · [멀티 AF](evidence/multi-af.png) · [드래그 후 MF](evidence/dragged-mf.png) | 가상의 Camera2 지원 정보를 주입해 실제 제품 레이아웃을 그린 Robolectric 화면. 실물 카메라 프레임 없음. |
| [이동 중 렌즈 선택 표시](evidence/lens-rail-mid-motion.png) | SDK 35 합성 HAL 레이아웃. 애니메이션 진행률을 30%로 설정한 프레임이다. 이후 렌즈 코드는 API 24/25 호출을 막는 조건과 줄바꿈만 바뀌었다. |

## 빌드 준비

JDK 17, Android SDK 36과 저장소의 Gradle 8.13 wrapper를 사용한다. Android SDK 경로와 `media3.patched.path`를 로컬 `local.properties`에 설정한다. [개인 포크 빌드 잠금](../../personal-build.lock.json)의 [patched Media3](https://github.com/anonfaded/media3-patched/tree/51c6a4f96d4dc08d421682d96ecc5a044ea1decb) 커밋 `51c6a4f96d4dc08d421682d96ecc5a044ea1decb`를 사용한다. 이 버전은 MP4 마무리 방식을 보존하므로 일반 Maven Media3로 대체하면 같은 빌드가 아니다.

[Droid UVC](https://github.com/mikimn/droiduvc) `0.2.1`은 [Maven Central 원본 AAR](https://repo.maven.apache.org/maven2/io/github/mikimn/droiduvc/0.2.1/droiduvc-0.2.1.aar)을 `app/libs/droiduvc-0.2.1.aar`에 두고 빌드한다. 필요한 파일의 SHA-256은 `a44a5dcb2be88318ebf4032e90e484192d3976eb19e299d2b516ae7d70fb1da0`이다. Gradle도 이 해시를 확인한다. 공개 커밋에는 이 바이너리나 APK를 포함하지 않는다. [서드파티 고지와 라이선스](../../app/src/main/assets/licenses/droiduvc/)는 함께 둔다.

설정을 마친 뒤 저장소 루트에서 `./gradlew :app:assembleDefaultDebug` 또는 Windows의 `gradlew.bat :app:assembleDefaultDebug`를 실행한다. 기기에 설치하는 작업은 별도이며, 물리 폰을 대상으로 할 때 기존 `--user 0` 제한을 유지한다.

## 다음에 판단할 항목

1. USB 연결·권한 승인/거절·분리/재연결의 생명주기, 모드 변경, 실제 영상 및 지원 초점 제어를 구매한 장치에서 확인한다.
2. 실물 휴대폰 검증은 별도 승인 범위로 다룬다. 중앙/멀티 AF와 물리 렌즈, 0.00–1.00 곡선, 회전 중 미리보기와 갤러리 실제 조작을 확인한다.
3. USB 영상 녹화와 데스크톱 개조는 별도 범위를 정한다. 이 문서는 현 상태를 이해하고 검수하기 위한 자료다.
