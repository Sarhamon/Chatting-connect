# Chatting Connect

치지직 · 아프리카(SOOP) 등 방송 채팅을 마인크래프트 채팅창에 연동하는 모드입니다.

현재는 **치지직 채팅 수신 → 게임 내 채팅창 표시**를 목표로 개발 중입니다.

## 지원 목표

| 로더 | 상태 | 비고 |
| --- | --- | --- |
| Forge | 개발 중 (우선) | MC 1.20.1 |
| NeoForge | 예정 | 1.20.1에서는 Forge와 사실상 동일, 1.20.2+에서 분기 |
| Fabric | 예정 | |

> 타겟 버전은 1.20.1로 시작하며, 이후 여러 버전으로 확장할 예정입니다.

## 기능

- [x] 치지직 방송 채팅을 게임 채팅창에 실시간 표시
- [ ] 게임 채팅 입력 → 방송 채팅으로 송신 (예정)
- [ ] 후원/도네이션 이벤트 (예정)
- [ ] 아프리카(SOOP) 지원 (예정)

## 프로젝트 구조

```
Chatting-connect/
├── common/                 # 로더 비의존 공용 로직 (ChzzkClient 등, 순수 Java)
├── chat-connect-forge/     # Forge 진입점 + 커맨드
├── chat-connect-neoforge/  # NeoForge 진입점 (예정)
├── chat-connect-fabric/    # Fabric 진입점 (예정)
├── build.gradle            # 루트 빌드 스크립트
├── settings.gradle         # 서브프로젝트 구성
└── gradle.properties       # 공용 버전 · 모드 메타데이터
```

공용 로직은 `common` 모듈에 두고, 각 로더 모듈이 이를 의존해 자신의 모드 jar에 포함합니다.
로더별로 다른 것은 진입점 · 커맨드 · 채팅 표시 같은 얇은 어댑터 계층뿐입니다.

## 빌드

JDK 17 필요.

```bash
# Forge 모드 빌드
./gradlew :chat-connect-forge:build

# 개발용 클라이언트 실행
./gradlew :chat-connect-forge:runClient
```

빌드 결과물: `chat-connect-forge/build/libs/`

## 사용법

게임 내에서 클라이언트 커맨드로 연결합니다. (클라이언트 사이드 모드라 서버 접속 여부와 무관하게 동작)

```
/chzzk connect <channelId>   # 치지직 채널 채팅에 연결 (channelId = chzzk.naver.com/<channelId>)
/chzzk disconnect            # 연결 종료
```

## 치지직 연동 방식

프로토타입 단계에서는 치지직 **비공식 내부 API**(REST로 채팅 채널/토큰 조회 → 웹소켓 수신)를 사용합니다.
외부 라이브러리 없이 Java 표준 `java.net.http` + 마인크래프트 번들 Gson만 사용합니다.
안정화 · 배포 단계에서 공식 Open API(OAuth) 전환을 검토합니다.

## 라이선스

All Rights Reserved (추후 결정)
