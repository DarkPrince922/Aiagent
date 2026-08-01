# Jarvis for Android

Нативный Android-ассистент для OpenAI-совместимого API. По умолчанию используется
`https://aiprovider.duckdns.org/v1` и модель `claude-opus-4-8`; оба значения меняются
в настройках приложения.

## Что уже работает

- чат через `POST /chat/completions`;
- локальные настройки endpoint, модели, API-ключа и system prompt;
- очередь запросов в SQLite и повтор через WorkManager после восстановления сети;
- инструменты времени, батареи, устройства, браузера, карт, набора номера, письма,
  отправки текста и настроек приложения;
- подтверждение пользователем перед внешними действиями.

## Запуск

Откройте корневую папку в Android Studio, дождитесь Gradle Sync и запустите модуль
`app` на Android 8.0 или новее. В настройках приложения укажите API-ключ провайдера.

## Сборка на GitHub

Workflow `.github/workflows/android.yml` собирает debug APK после каждого push в
`main`, для pull request и при ручном запуске. Готовый файл находится на странице
запуска GitHub Actions в артефакте `jarvis-debug-apk`.

Проект намеренно не хранит ключ в репозитории. Для production-версии следует
добавить EncryptedSharedPreferences/Android Keystore и проверить точный формат
tool calling у выбранного API-провайдера.
