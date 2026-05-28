#!/bin/bash
# 파일을 doc-viewer에 등록하고 변환하는 스크립트
# 사용법: ./register.sh <파일경로> [키이름]
#
# 예시:
#   ./register.sh ~/Downloads/sample.hwp
#   ./register.sh ~/Downloads/report.docx MY_KEY_0

FILE="$1"
KEY="$2"

DOC_VIEWER_URL="http://localhost:8090"

# 인자 검증
if [ -z "$FILE" ]; then
  echo "사용법: ./register.sh <파일경로> [키이름]"
  echo ""
  echo "예시:"
  echo "  ./register.sh ~/Downloads/sample.hwp"
  echo "  ./register.sh ~/Downloads/report.docx MY_KEY_0"
  exit 1
fi

# 절대 경로로 변환
FILE=$(realpath "$FILE" 2>/dev/null || python3 -c "import os,sys; print(os.path.abspath(sys.argv[1]))" "$FILE")

if [ ! -f "$FILE" ]; then
  echo "오류: 파일을 찾을 수 없습니다 → $FILE"
  exit 1
fi

ORIG_NAME=$(basename "$FILE")

# 키 자동 생성 (미입력 시)
if [ -z "$KEY" ]; then
  BASE=$(basename "$FILE" | sed 's/\.[^.]*$//' | tr '[:lower:]' '[:upper:]' | tr -cd '[:alnum:]_-')
  KEY="TEST_${BASE}_0"
fi

echo "──────────────────────────────────"
echo "파일:     $ORIG_NAME"
echo "경로:     $FILE"
echo "키:       $KEY"
echo "──────────────────────────────────"
echo "등록 중..."

RESPONSE=$(curl -s -X POST "$DOC_VIEWER_URL/docviewer/api/convert" \
  -H "Content-Type: application/json" \
  -d "{\"key\":\"$KEY\",\"path\":\"$FILE\",\"originalName\":\"$ORIG_NAME\",\"fileHash\":\"\"}")

# 응답 확인
if echo "$RESPONSE" | grep -q '"status":"ok"'; then
  echo "✓ 등록 및 변환 완료"
  echo ""
  echo "뷰어 URL:"
  echo "  $DOC_VIEWER_URL/docviewer/view?key=$KEY"
  echo ""
  echo "브라우저에서 열기:"
  # macOS
  if command -v open &>/dev/null; then
    read -p "지금 바로 브라우저로 열까요? [y/N] " OPEN
    if [ "$OPEN" = "y" ] || [ "$OPEN" = "Y" ]; then
      open "$DOC_VIEWER_URL/docviewer/view?key=$KEY"
    fi
  fi
else
  echo "✗ 오류 발생:"
  echo "$RESPONSE"
  exit 1
fi
