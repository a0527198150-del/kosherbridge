package com.example.kosherbridge.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.kosherbridge.BridgeService
import com.example.kosherbridge.bluetooth.AdbShell
import kotlinx.coroutines.launch

/**
 * Sets up the in-app ADB channel: the shell identity, obtained from the
 * player's own daemon, with no PC, no root and no second app.
 *
 * The screen is mostly instructions on purpose. Every step here happens in
 * Android's own Settings, the wording differs between versions, and the one
 * mistake everybody makes - entering the port from the wireless-debugging
 * screen instead of the port from the pairing dialog, which is a different
 * number - produces a failure with no useful message. So the steps are spelled
 * out in order, each with the button that opens the right screen.
 */
@Composable
fun AdbChannelScreen(
  onSnackbar: (String) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var shell by remember { mutableStateOf<AdbShell?>(null) }
  var missing by remember { mutableStateOf(false) }
  var pairPort by remember { mutableStateOf("") }
  var pairCode by remember { mutableStateOf("") }
  var connectPort by remember { mutableStateOf("") }
  var result by remember { mutableStateOf<String?>(null) }
  var busy by remember { mutableStateOf(false) }
  // Bumped after every action so the state line re-reads the channel rather
  // than showing whatever it said when the screen opened.
  var refresh by remember { mutableIntStateOf(0) }

  androidx.compose.runtime.LaunchedEffect(refresh) {
    BridgeService.withManager(context, onMissing = { missing = true }) { bridge ->
      missing = false
      shell = bridge.adbShell
    }
  }

  val state = shell?.state
  val stateText = when {
    missing -> "שירות הגשר לא פעיל - פתח את המסך הראשי וחזור לכאן"
    shell == null -> "ערוץ ADB אינו זמין בגרסה הזו (דרושה גרסת 'פלוס', או אנדרואיד 11 ומעלה)"
    state == AdbShell.State.CONNECTED -> "מחובר. ערוץ ההרשאות פעיל"
    state == AdbShell.State.PAIRED -> "מותאם, אך לא מחובר כרגע"
    else -> "עדיין לא מותאם"
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SubPageHeader("ערוץ ADB מקומי", onBack)

    Card(
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(
        containerColor = if (state == AdbShell.State.CONNECTED) {
          MaterialTheme.colorScheme.secondaryContainer
        } else {
          MaterialTheme.colorScheme.surfaceVariant
        },
      ),
    ) {
      Column(Modifier.padding(16.dp)) {
        Text(stateText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
          "הערוץ הזה נותן לאפליקציה זהות shell מה-ADB של הנגן עצמו - בלי מחשב, " +
            "בלי רוט ובלי Shizuku. זה מה שמאפשר לפתוח את שער השמע, לתקן מדיניות " +
            "חיבור, ובנגן עם SELinux מתירני גם להדליק את פרופיל הדיבורית.",
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }

    result?.let { text ->
      Card(shape = RoundedCornerShape(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
      }
    }

    SettingsCard("שלב 1 - הדלק ניפוי באגים אלחוטי") {
      Text(
        "הגדרות ← אפשרויות מפתח ← ניפוי באגים אלחוטי. אם 'אפשרויות מפתח' לא " +
          "מופיעות, הגדרות ← אודות ← לחץ שבע פעמים על 'מספר בנייה'.",
        style = MaterialTheme.typography.bodySmall,
      )
      SettingRow("פתח את אפשרויות המפתח", "אם הכפתור לא עובד, לך לשם ידנית") {
        if (!openDeveloperOptionsScreen(context)) {
          onSnackbar("לא ניתן לפתוח את המסך אוטומטית - פתח אותו ידנית בהגדרות")
        }
      }
    }

    SettingsCard("שלב 2 - התאמה (פעם אחת בלבד)") {
      Text(
        "באותו מסך, לחץ 'התאמת מכשיר עם קוד התאמה'. ייפתח חלון עם קוד בן שש " +
          "ספרות ועם כתובת בצורה 192.168.x.x:PORT. העתק לכאן את המספר שאחרי " +
          "הנקודתיים ואת הקוד - ואל תסגור את החלון עד שההתאמה הצליחה.",
        style = MaterialTheme.typography.bodySmall,
      )
      Text(
        "שים לב: היציאה בחלון ההתאמה שונה מהיציאה שמוצגת במסך ניפוי הבאגים " +
          "עצמו. זו הטעות הנפוצה ביותר כאן.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = pairPort,
          onValueChange = { pairPort = it.filter { c -> c.isDigit() }.take(5) },
          label = { Text("יציאה") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.width(120.dp),
        )
        OutlinedTextField(
          value = pairCode,
          onValueChange = { pairCode = it.filter { c -> c.isDigit() }.take(6) },
          label = { Text("קוד התאמה") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          modifier = Modifier.weight(1f),
        )
      }
      Button(
        onClick = {
          val s = shell
          val port = pairPort.toIntOrNull()
          when {
            s == null -> onSnackbar("ערוץ ADB אינו זמין בגרסה הזו")
            port == null -> onSnackbar("הזן את מספר היציאה מחלון ההתאמה")
            else -> scope.launch {
              busy = true
              result = "מתאים..."
              result = s.pair(port, pairCode)
              busy = false
              refresh++
            }
          }
        },
        enabled = !busy && shell != null,
      ) { Text("התאם") }
    }

    SettingsCard("שלב 3 - התחבר") {
      Text(
        "אחרי התאמה מוצלחת, ההתחברות היא הפעולה היחידה שצריך לחזור עליה - " +
          "מספר היציאה של החיבור מוגרל מחדש בכל הדלקה של הנגן, ולכן האפליקציה " +
          "מנסה לגלות אותו לבד. אם הגילוי נכשל, הזן את היציאה שמוצגת במסך ניפוי " +
          "הבאגים האלחוטי (לא זו של ההתאמה).",
        style = MaterialTheme.typography.bodySmall,
      )
      OutlinedTextField(
        value = connectPort,
        onValueChange = { connectPort = it.filter { c -> c.isDigit() }.take(5) },
        label = { Text("יציאה (רשות)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
      )
      Button(
        onClick = {
          val s = shell
          if (s == null) {
            onSnackbar("ערוץ ADB אינו זמין בגרסה הזו")
          } else {
            scope.launch {
              busy = true
              result = "מתחבר..."
              result = s.connect(connectPort.toIntOrNull())
              busy = false
              refresh++
            }
          }
        },
        enabled = !busy && shell != null,
      ) { Text("התחבר") }
      SettingRow(
        "בדוק את הזהות",
        "מריץ id דרך הערוץ ומראה בדיוק כמי האפליקציה פועלת",
      ) {
        val s = shell
        if (s == null) {
          onSnackbar("ערוץ ADB אינו זמין בגרסה הזו")
        } else {
          scope.launch {
            result = "בודק..."
            val id = s.exec("id")
            result = if (id.isBlank()) {
              "אין תשובה - הערוץ כנראה לא מחובר"
            } else {
              // uid=2000(shell) is the whole point, so show it verbatim rather
              // than interpreting it.
              id
            }
            refresh++
          }
        }
      }
    }

    SettingsCard("אחרי שזה עובד") {
      Text(
        "בחר הגדרות ← כל הגדרות החיבור ← 'ערוץ חיבור' ← 'דרך ADB מקומי'. " +
          "ההתאמה נשמרת בנגן, כך שאחרי הדלקה מחדש צריך רק להתחבר שוב.",
        style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}
