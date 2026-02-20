# Play Console 권한 설문/앱 설명 문구

- 앱은 시스템 사진 선택기(`ActivityResultContracts.GetMultipleContents`)를 사용합니다.
- 사용자가 선택한 이미지 항목에 한해서만 접근하며, 기기 전체 미디어를 일괄 조회하지 않습니다.
- 따라서 Android 13+의 `READ_MEDIA_IMAGES` 권한을 요청하지 않습니다.
