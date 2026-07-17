# MiAgenda (Android)

App nativa Android (Kotlin + Jetpack Compose) que:
1. Captura tu voz con el reconocimiento de voz del sistema.
2. Interpreta el texto **localmente, sin ninguna API ni costo**, con un
   parser en Kotlin basado en reglas que entiende "mañana", "el lunes",
   "en 2 horas", horas con o sin am/pm, etc.
3. Programa dos alarmas exactas (`AlarmManager`): un aviso 15 minutos antes y
   una a la hora exacta.
4. Cuando suenan, abren una pantalla completa con sonido de alarma (volumen
   de alarma del sistema, no el de notificaciones) y lo lee en voz alta con
   Text-to-Speech. Se puede posponer 10 minutos o descartar.

No requiere internet para interpretar el recordatorio (solo el reconocimiento
de voz del sistema puede usarlo, según el motor de voz de tu teléfono).

## 1. Requisitos

- Android Studio (Koala o más reciente) — https://developer.android.com/studio
- Un celular o emulador con Android 8.0 (API 26) o superior

## 2. Abrir y correr el proyecto

1. Abre Android Studio → "Open" → selecciona la carpeta `RecordatorioVoz`.
2. Si te pide crear el Gradle Wrapper (el `gradle-wrapper.jar` binario no se
   generó desde aquí), acepta — Android Studio lo crea automáticamente al
   sincronizar.
3. Espera el "Gradle Sync".
4. Conecta tu celular por USB con "Depuración USB" activada, o crea un
   emulador (AVD) con Play Store (necesario para que el reconocimiento de voz
   funcione bien).
5. Run ▶.

(Opcional) Si Android Studio no te autocompleta la ruta del SDK, copia
`local.properties.example` como `local.properties` y ajusta `sdk.dir`.

## 3. Cómo hablarle para que interprete bien

El parser local es más limitado que un LLM — para evitar que interprete mal
la fecha/hora, sigue estas convenciones al dictar:

- Di **"a las"** antes de la hora: _"a las 5 pm"_, _"a las 17:30"_.
- Si no dices am/pm, las horas de 1 a 7 se asumen PM (tarde/noche) por ser lo
  más común en recordatorios del día a día — para la mañana, di "am" o
  "de la mañana" explícitamente.
- Frases relativas soportadas: `hoy`, `mañana`, `pasado mañana`, los días de
  la semana (`el lunes`, `martes`, etc. → se agenda para la próxima vez que
  ocurra ese día), y `en N minutos/horas/días`.
- Ejemplos que funcionan bien:
  - "mañana a las 5 pm sácame la basura"
  - "el lunes tengo reunión con el cliente Juan a las 5"
  - "recuérdame en 20 minutos llamar al banco"
  - "el viernes a las 9 am dentista"

Si la fecha/hora no se pudo interpretar razonablemente, la app muestra un
mensaje de error en pantalla en vez de agendar algo incorrecto.

## 4. Permisos que el usuario debe activar manualmente

Estos NO se pueden forzar por código (restricción de Android), actívalos la
primera vez que uses la app:

- **Alarmas exactas**: la app te lleva directo a la pantalla de ajustes si
  falta (botón en la UI principal). En Android 12+ es obligatorio para que
  las alarmas suenen a la hora exacta.
- **Optimización de batería**: en Ajustes → Apps → MiAgenda →
  Batería → "Sin restricciones". Si no, el sistema puede matar la app y
  retrasar/perder alarmas.
- **Notificaciones**: se pide en tiempo de ejecución (Android 13+), pero si
  la niegas, no vas a ver la pantalla de alarma.
- **No Molestar**: algunos fabricantes (Xiaomi, Samsung, Huawei) aplican
  restricciones extra de "auto-inicio" / "apps en segundo plano" que hay que
  permitir manualmente por fabricante — es una limitación conocida del
  ecosistema Android, no de esta app.

## 5. Estructura del proyecto

```
app/src/main/java/com/recordatorios/voz/
├── data/          Room: entidad Reminder, DAO, base de datos
├── parser/        LocalReminderParser: interpretación de fecha/hora por reglas
├── alarm/         AlarmScheduler, AlarmReceiver, AlarmActivity, BootReceiver
└── ui/            MainActivity (Compose): botón de micrófono + lista
```

## 6. Flujo de datos

```
[Botón mic] → RecognizerIntent (STT del sistema) → texto plano
     → LocalReminderParser.parse(texto) → {title, triggerAtMillis}
     → Room (guarda el Reminder)
     → AlarmScheduler.scheduleAll() → AlarmManager (2 alarmas exactas)

[Alarma dispara] → AlarmReceiver → notificación fullScreenIntent
     → AlarmActivity (pantalla completa)
        ├─ MediaPlayer en loop, STREAM_ALARM al volumen máximo
        ├─ TextToSpeech lee el mensaje en voz alta
        ├─ botón "Posponer 10 min" → reprograma con AlarmScheduler.scheduleSnooze
        └─ botón "Descartar" → cierra todo
```

## 7. Pendientes / próximos pasos sugeridos

- [ ] Botón para eliminar/editar recordatorios desde la lista.
- [ ] Pantalla de confirmación antes de guardar (mostrar lo que se entendió
      —título y fecha/hora— antes de programar la alarma, por si el parser
      interpretó mal algo).
- [ ] Ampliar el parser local: rangos como "de 3 a 4", fechas explícitas
      ("el 15 de agosto"), o "la próxima semana".
- [ ] Si más adelante decides que vale la pena el costo de una API de IA
      (aunque sea mínimo) para mayor precisión con frases ambiguas, se puede
      reintroducir sin tocar el resto de la app — solo reemplaza
      `LocalReminderParser.parse()` por la llamada externa, ya que ambos
      devuelven el mismo tipo `ParsedReminder`.
