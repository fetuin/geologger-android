package com.usepocket.geologger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .background(if (selected) Hue.pillOn else Hue.pillOff, RoundedCornerShape(18.dp))
            .border(1.dp, if (selected) Hue.accent else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            color = if (selected) Hue.pillOnText else Hue.dim,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** 縦に積む選択肢。説明文を添えたいときに使う。 */
@Composable
fun ChoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(if (selected) Hue.pillOn else Color(0xFF1C2024), RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) Hue.accent else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(
            title,
            color = if (selected) Hue.pillOnText else Hue.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(subtitle, color = Hue.faint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    plain: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = when {
                danger -> Color(0xFF5C1F22)
                plain -> Color(0xFF23272C)
                else -> Hue.accent
            },
            contentColor = if (danger) Color(0xFFFFD9D9) else Hue.text,
            disabledContainerColor = Color(0xFF1A1D21),
            disabledContentColor = Hue.faint,
        )
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    error: String? = null,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(label, color = Hue.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            isError = error != null,
            placeholder = { Text(placeholder, color = Hue.faint, fontSize = 14.sp) },
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard),
            textStyle = androidx.compose.ui.text.TextStyle(color = Hue.text, fontSize = 16.sp),
            shape = RoundedCornerShape(9.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Hue.field,
                unfocusedContainerColor = Hue.field,
                errorContainerColor = Hue.field,
                focusedIndicatorColor = Hue.accent,
                unfocusedIndicatorColor = Hue.fieldEdge,
                errorIndicatorColor = Hue.bad,
                cursorColor = Hue.accent,
            ),
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Text(error, color = Hue.bad, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Hue.text, fontSize = 14.sp)
            Text(subtitle, color = Hue.faint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Hue.accent,
                uncheckedTrackColor = Color(0xFF333333),
            )
        )
    }
}

/** 数字を3つ横並びにする統計表示。 */
@Composable
fun StatRow(items: List<Pair<String, String>>) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        items.forEach { (value, key) ->
            Column {
                Text(value, color = Hue.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(key, color = Hue.faint, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
