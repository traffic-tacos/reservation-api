# GitHub Actions CI/CD for reservation-api

이 디렉토리는 reservation-api의 CI/CD 파이프라인을 관리합니다.

## 워크플로우

### build.yml - Build, Test and Deploy

**트리거:**
- `main`, `develop` 브랜치에 push
- `main`, `develop` 브랜치로의 Pull Request

**주요 작업:**

1. **Test & Lint**
   - JDK 17 환경 설정 (Temurin)
   - Gradle 캐싱 활성화
   - 유닛 테스트 실행
   - Kotlin 린트 검사 (ktlint)
   - 테스트 결과 및 커버리지 리포트 발행

2. **Build & Push Docker Image**
   - Gradle을 통한 애플리케이션 빌드
   - Docker Buildx를 통한 멀티 플랫폼 빌드 (linux/amd64)
   - AWS ECR에 이미지 푸시
   - 이미지 태그: `{git-short-sha}`, `latest`
   - Trivy를 통한 보안 취약점 스캔

3. **Update Deployment Manifest** (main 브랜치만)
   - `deployment-repo`에 repository_dispatch 이벤트 전송
   - 자동으로 Kubernetes manifest의 이미지 태그 업데이트
   - ArgoCD가 자동으로 변경사항을 감지하고 배포

## 필수 GitHub Secrets

이 워크플로우를 실행하려면 다음 Secrets를 설정해야 합니다:

### 1. AWS_ROLE_ARN
AWS OIDC 인증을 위한 IAM Role ARN

**설정 값:**
```
arn:aws:iam::137406935518:role/GitHubActionsRole
```

**필요한 권한:**
- `ecr:GetAuthorizationToken`
- `ecr:BatchCheckLayerAvailability`
- `ecr:GetDownloadUrlForLayer`
- `ecr:BatchGetImage`
- `ecr:PutImage`
- `ecr:InitiateLayerUpload`
- `ecr:UploadLayerPart`
- `ecr:CompleteLayerUpload`

### 2. DEPLOYMENT_REPO_TOKEN
deployment-repo에 접근하기 위한 GitHub Personal Access Token (PAT)

**필요한 권한:**
- `repo` (전체 저장소 접근)
- 또는 최소한: `repo:status`, `repo_deployment`, `public_repo`

**생성 방법:**
1. GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. "Generate new token (classic)" 클릭
3. 필요한 권한 선택
4. 토큰 생성 후 복사

**설정 위치:**
- Repository Settings → Secrets and variables → Actions
- "New repository secret" 클릭
- Name: `DEPLOYMENT_REPO_TOKEN`
- Secret: [생성한 PAT 붙여넣기]

## 이미지 태그 전략

### main 브랜치
```
{short-sha}  # 예: a1b2c3d
latest
```

### develop/기타 브랜치
```
{branch}-{short-sha}  # 예: develop-a1b2c3d
latest
```

## 배포 프로세스

1. **코드 Push** → `main` 브랜치
2. **GitHub Actions 실행**
   - 테스트 및 린트
   - Gradle 빌드
   - Docker 이미지 빌드 및 ECR 푸시
3. **Repository Dispatch** → `deployment-repo`
4. **Manifest 업데이트** → `manifests/reservation-api/deployment.yaml`
5. **ArgoCD 자동 동기화** → Kubernetes 클러스터에 배포
6. **Pod 재시작** → 새 이미지로 업데이트

## 모니터링

### ArgoCD
```
https://argocd.traffictacos.store/applications/reservation-api
```

### Kubernetes
```bash
kubectl get pods -n tacos-app -l app=reservation-api
kubectl logs -n tacos-app -l app=reservation-api --tail=100
```

### ECR
```bash
aws ecr describe-images \
  --repository-name traffic-tacos-reservation-api \
  --region ap-northeast-2 \
  --query 'reverse(sort_by(imageDetails,& imagePushedAt))[:5]'
```

## 트러블슈팅

### 워크플로우 실패 시

1. **테스트 실패**
   - Actions 탭에서 로그 확인
   - 로컬에서 `./gradlew test` 실행

2. **빌드 실패**
   - Gradle 빌드 로그 확인
   - 로컬에서 `./gradlew build` 실행

3. **린트 실패**
   - ktlint 에러 확인
   - 로컬에서 `./gradlew ktlintFormat` 실행 (자동 수정)

4. **ECR 푸시 실패**
   - `AWS_ROLE_ARN` Secret 확인
   - IAM Role의 신뢰 관계 및 권한 확인

5. **Deployment 업데이트 실패**
   - `DEPLOYMENT_REPO_TOKEN` Secret 확인
   - PAT 권한 및 만료일 확인
   - deployment-repo의 webhook 로그 확인

## 로컬 테스트

### 유닛 테스트 실행
```bash
./gradlew test
```

### 린트 검사
```bash
./gradlew ktlintCheck
```

### 린트 자동 수정
```bash
./gradlew ktlintFormat
```

### 애플리케이션 빌드
```bash
./gradlew build
```

### Docker 이미지 빌드
```bash
docker build --platform linux/amd64 -t reservation-api:local .
```

### Docker 이미지 실행
```bash
docker run --rm -p 8010:8010 \
  -e SERVER_PORT=8010 \
  -e GRPC_PORT=8011 \
  -e REDIS_ADDRESS=localhost:6379 \
  -e DB_HOST=localhost \
  -e DB_PORT=5432 \
  -e DB_NAME=reservation \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=password \
  reservation-api:local
```

## Reservation API 특징

reservation-api는 Traffic Tacos의 예약 관리 서비스로서 다음 기능을 제공합니다:

- **예약 생성/조회/취소**: 좌석 예약 CRUD 작업
- **재고 검증**: inventory-api와 통신하여 재고 확인
- **Redis 캐싱**: 성능 향상을 위한 캐싱
- **PostgreSQL**: 예약 데이터 영구 저장
- **gRPC & REST**: 이중 프로토콜 지원
- **분산 추적**: OpenTelemetry를 통한 모니터링

### 환경 변수

| 변수명 | 설명 | 예시 |
|--------|------|------|
| `SERVER_PORT` | HTTP 서버 포트 | `8010` |
| `GRPC_PORT` | gRPC 서버 포트 | `8011` |
| `REDIS_ADDRESS` | Redis 주소 | `master.xxx.cache.amazonaws.com:6379` |
| `REDIS_TLS_ENABLED` | Redis TLS 사용 여부 | `true` |
| `REDIS_TLS_INSECURE_SKIP_VERIFY` | TLS 인증서 검증 스킵 | `true` |
| `DB_HOST` | PostgreSQL 호스트 | `db.example.com` |
| `DB_PORT` | PostgreSQL 포트 | `5432` |
| `DB_NAME` | 데이터베이스 이름 | `reservation` |
| `DB_USERNAME` | DB 사용자명 | `postgres` |
| `DB_PASSWORD` | DB 비밀번호 | `password` |
| `INVENTORY_GRPC_ADDR` | Inventory API gRPC 주소 | `inventory-api:8020` |

## 기술 스택

- **언어**: Kotlin 1.9.25
- **프레임워크**: Spring Boot 3.x
- **빌드 도구**: Gradle (Kotlin DSL)
- **데이터베이스**: PostgreSQL
- **캐시**: Redis
- **프로토콜**: gRPC, REST API
- **모니터링**: OpenTelemetry

## 참고 자료

- [GitHub Actions 문서](https://docs.github.com/en/actions)
- [AWS ECR 문서](https://docs.aws.amazon.com/ecr/)
- [ArgoCD 문서](https://argo-cd.readthedocs.io/)
- [Gradle 문서](https://docs.gradle.org/)
- [Kotlin 문서](https://kotlinlang.org/docs/home.html)
- [Spring Boot 문서](https://spring.io/projects/spring-boot)

