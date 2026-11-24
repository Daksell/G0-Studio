# G0-Studio
This program is for a guitar pickup Double G0 Pro

## Выпуск 0.4.5

G0 Studio управляет гитарным звукоснимателем Double G0 Pro по Bluetooth. Обычная сборка 0.4.5 включает эквалайзер, управление педалями, быстрыми слотами и библиотекой пресетов.

## Сборка

Нужны Python 3.9 или новее, JDK 17, Android SDK Platform 35 и Android SDK Build-Tools 35.0.0. Запустите команду из этой папки. Скрипт использует SDK из `ANDROID_SDK_ROOT`, `ANDROID_HOME` или из рабочей папки проекта, если он там установлен.

```powershell
python .\build.py
```

Без параметров скрипт собирает `build/G0Studio-0.4.5-unsigned.apk`. Если SDK находится в другом месте, передайте его путь через `--sdk`. Для подписанного APK укажите файл ключа через `--keystore`. Чтобы обновить установленную сборку с сохранением данных, используйте её ключ подписи и номер сборки выше 75.

```powershell
python .\build.py --keystore 'C:\путь\к\ключу.jks' --version-code 76
```

В исходном выпуске 0.4.5 внутренний номер сборки равен 52. Установочный APK, подготовленный для возврата с тестовой версии, имеет номер 75. Имя пакета в обоих случаях `ru.g0studio`.

Установочный APK находится в [releases/G0Studio-0.4.5-restored.apk](releases/G0Studio-0.4.5-restored.apk).
