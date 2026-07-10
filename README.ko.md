[English](README.md) | **한국어**

# Chatting Connect

치지직 · 트위치 방송 채팅을 마인크래프트 채팅창에 연동하는 모드입니다.

**방송 채팅·후원·이모티콘을 게임 내 채팅창에 실시간 표시**합니다. (클라이언트 사이드 모드)

> SOOP(아프리카) 연동도 구현되어 있으나, 미션 후원 처리가 아직 확인되지 않아 **현재 릴리스에서는 비활성화**되어 있습니다.

## 지원 목표

| 로더 | 상태 | 비고 |
| --- | --- | --- |
| Forge | 지원 | MC 1.20.1 |
| Fabric | 지원 | MC 1.20.1, Fabric API 필요 |
| NeoForge | 지원 | MC 1.20.1 (`net.neoforged:forge` 47.1.x). 1.20.1에선 Forge와 API 동일해 forge 소스를 재사용, 1.20.2+에서 분기 |

> 타겟 버전은 1.20.1로 시작하며, 이후 여러 버전으로 확장할 예정입니다.

## 기능

- [x] 치지직 · 트위치 방송 채팅을 게임 채팅창에 실시간 표시
- [x] 이모티콘 인라인 이미지 렌더링 (치지직 · 트위치)
- [x] 후원/도네이션 표시 (치지직 치즈 · 트위치 비트)
- [x] 연결 끊김 시 자동 재연결 · 마지막 채널 자동 접속
- [x] Forge · Fabric · NeoForge 멀티로더 지원
- [ ] 게임 채팅 입력 → 방송 채팅으로 송신 (예정)

## 프로젝트 구조

```
Chatting-connect/
├── common/                 # 로더 비의존 공용 로직 (ChzzkClient 등, 순수 Java)
├── chat-connect-forge/     # Forge 진입점 + 커맨드 + 이모티콘 렌더(믹스인)
├── chat-connect-fabric/    # Fabric 진입점 + 커맨드 + 이모티콘 렌더(믹스인)
├── chat-connect-neoforge/  # NeoForge 1.20.1 (build.gradle만; forge 소스 재사용)
├── build.gradle            # 루트 빌드 스크립트
├── settings.gradle         # 서브프로젝트 구성
└── gradle.properties       # 공용 버전 · 모드 메타데이터
```

공용 로직은 `common` 모듈에 두고, 각 로더 모듈이 이를 의존해 자신의 모드 jar에 포함합니다.
로더별로 다른 것은 진입점 · 커맨드 · 채팅 표시 같은 얇은 어댑터 계층뿐입니다.

## 빌드

모드는 **Java 17**을 타깃하지만(각 모듈 toolchain), Fabric Loom 1.12 요구사항으로 **Gradle 데몬은 Java 21+** 에서 실행됩니다(`gradle/gradle-daemon-jvm.properties`). Java 21이 없으면 Gradle이 toolchain 자동 프로비저닝으로 받아옵니다.

```bash
# Forge 모드 빌드 / 실행
./gradlew :chat-connect-forge:build
./gradlew :chat-connect-forge:runClient

# Fabric 모드 빌드 / 실행 (개발 실행 시 Fabric API 자동 포함)
./gradlew :chat-connect-fabric:build
./gradlew :chat-connect-fabric:runClient

# NeoForge 모드 빌드 / 실행
./gradlew :chat-connect-neoforge:build
./gradlew :chat-connect-neoforge:runClient
```

빌드 결과물: `chat-connect-<loader>/build/libs/`

## 설치

1. 사용하는 모드 로더(Forge · Fabric · NeoForge, **MC 1.20.1**)를 설치합니다.
2. 로더에 맞는 `chattingconnect-<loader>-1.20.1-*.jar` 를 `mods` 폴더에 넣습니다.
3. **Fabric 사용 시 [Fabric API](https://modrinth.com/mod/fabric-api) 도 함께** `mods` 폴더에 넣어야 합니다. (Forge/NeoForge는 추가 의존성 없음)
4. 클라이언트 사이드 모드라 서버에는 설치할 필요가 없습니다.

## 사용법

게임 내에서 클라이언트 커맨드로 연결합니다. (클라이언트 사이드 모드라 서버 접속 여부와 무관하게 동작)

```
/chzzk  connect <channelId>   # 치지직 채널 (channelId = chzzk.naver.com/<channelId>)
/twitch connect <login>       # 트위치 채널 (login = 로그인명, 소문자 영문)

/<플랫폼> disconnect          # 연결 종료
```

한 번 연결한 채널은 `config/chattingconnect.json` 에 저장되어, 다음 실행 시 자동으로 다시 접속합니다.
`disconnect` 하면 저장이 삭제되어 자동 접속하지 않습니다.

## 연동 방식

외부 라이브러리 없이 Java 표준 `java.net.http`(HttpClient · WebSocket) + 마인크래프트 번들 Gson만 사용합니다.

- **치지직 · SOOP**: **비공식 내부 API**(REST로 채팅 채널/토큰 조회 → 웹소켓 수신). 안정화·배포 단계에서 공식 API 전환을 검토합니다.
- **트위치**: 공개 **IRC-over-WebSocket**(`irc-ws.chat.twitch.tv`)에 인증 없이 **익명 읽기 전용**으로 접속합니다. 트위치 1차 이모티콘과 비트(cheer)만 지원하며, 서드파티 이모티콘(BTTV/FFZ/7TV)과 외부 현금 도네이션은 채팅 프로토콜에 포함되지 않아 지원하지 않습니다.

## 라이선스

[MIT License](LICENSE.md) © 2026 sarhamon

> 이 모드는 치지직·SOOP의 **비공식 내부 API**와 트위치 IRC를 사용합니다. MIT는 이 프로젝트 **코드**에만 적용되며,
> 각 플랫폼의 서비스 약관 준수 책임은 사용자에게 있습니다.
