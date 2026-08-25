package top.r2dblog.justcamera.ui.filter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.r2dblog.justcamera.filter.builtin.BuiltInFilterCatalog
import top.r2dblog.justcamera.filter.builtin.BuiltInPresets
import top.r2dblog.justcamera.filter.model.FilterDescriptor
import top.r2dblog.justcamera.filter.model.FilterParameterSpec
import top.r2dblog.justcamera.filter.model.FilterParameterValue
import top.r2dblog.justcamera.filter.model.FilterParameters
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun FilterScreen(onBack: () -> Unit) {
    val descriptors = remember { BuiltInFilterCatalog.registry().descriptors() }
    var selected by remember { mutableStateOf(descriptors.first()) }
    var parameters by remember(selected.id) {
        mutableStateOf(FilterParameters.defaults(selected))
    }
    var enabled by remember(selected.id) { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101114))
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Filter Engine", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "PREVIEW configuration · CPU sample/final API",
                    color = Color.White.copy(alpha = 0.62f),
                )
            }
            Button(onClick = onBack) { Text("Back") }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "The live TextureView is not pixel-filtered in PH3. Controls configure the shared " +
                "filter model; realtime rendering remains a later accelerated integration.",
            color = Color(0xFFFFC66D),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(18.dp))
        Text("Built-in filters", color = Color.White, fontWeight = FontWeight.Bold)
        descriptors.forEach { descriptor ->
            val selectedColor = if (descriptor.id == selected.id) Color(0xFF304A68) else Color(0xFF202227)
            Surface(
                color = selectedColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable { selected = descriptor },
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(descriptor.displayName, color = Color.White)
                    Text(descriptor.category.name, color = Color.White.copy(alpha = 0.55f))
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        FilterEditor(
            descriptor = selected,
            parameters = parameters,
            enabled = enabled,
            onEnabledChange = { enabled = it },
            onParametersChange = { parameters = it },
            onReset = { parameters = FilterParameters.defaults(selected) },
        )
        Spacer(Modifier.height(18.dp))
        Text("Reference looks", color = Color.White, fontWeight = FontWeight.Bold)
        BuiltInPresets.all.forEach { preset ->
            Text(
                "${preset.name} · ${preset.chain.operations.size} operation(s)",
                color = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun FilterEditor(
    descriptor: FilterDescriptor,
    parameters: FilterParameters,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onParametersChange: (FilterParameters) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(descriptor.displayName, color = Color.White, fontWeight = FontWeight.Bold)
            Text("Enabled", color = Color.White.copy(alpha = 0.65f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
            Button(onClick = onReset, modifier = Modifier.padding(start = 8.dp)) { Text("Reset") }
        }
    }
    descriptor.parameterSpecs.forEach { spec ->
        ParameterControl(spec, parameters) { value ->
            onParametersChange(parameters.copy(values = parameters.values + (spec.key to value)))
        }
    }
}

@Composable
private fun ParameterControl(
    spec: FilterParameterSpec,
    parameters: FilterParameters,
    onValueChange: (FilterParameterValue) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (spec) {
            is FilterParameterSpec.FloatParameter -> {
                val value = parameters.float(spec.key, spec.default)
                Text(
                    "${spec.label}: ${String.format(Locale.getDefault(), "%.2f", value)}",
                    color = Color.White,
                )
                Slider(
                    value = value,
                    onValueChange = { onValueChange(FilterParameterValue.FloatValue(it)) },
                    valueRange = spec.minimum..spec.maximum,
                    steps = sliderSteps(spec.minimum, spec.maximum, spec.step),
                )
            }
            is FilterParameterSpec.IntParameter -> {
                val value = parameters.int(spec.key, spec.default)
                Text("${spec.label}: $value", color = Color.White)
                Slider(
                    value = value.toFloat(),
                    onValueChange = {
                        val stepCount = (
                            (it - spec.minimum.toFloat()) / spec.step.toFloat()
                            ).roundToInt()
                        val stepped = (spec.minimum + stepCount * spec.step)
                            .coerceIn(spec.minimum, spec.maximum)
                        onValueChange(FilterParameterValue.IntValue(stepped))
                    },
                    valueRange = spec.minimum.toFloat()..spec.maximum.toFloat(),
                    steps = sliderSteps(
                        spec.minimum.toFloat(),
                        spec.maximum.toFloat(),
                        spec.step.toFloat(),
                    ),
                )
            }
            is FilterParameterSpec.BooleanParameter -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(spec.label, color = Color.White)
                Switch(
                    checked = parameters.boolean(spec.key, spec.default),
                    onCheckedChange = { onValueChange(FilterParameterValue.BooleanValue(it)) },
                )
            }
            is FilterParameterSpec.EnumParameter -> {
                val value = parameters.enum(spec.key, spec.default)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(spec.label, color = Color.White)
                    Button(onClick = {
                        val index = spec.allowedValues.indexOf(value).coerceAtLeast(0)
                        val next = spec.allowedValues[(index + 1) % spec.allowedValues.size]
                        onValueChange(FilterParameterValue.EnumValue(next))
                    }) { Text(value) }
                }
            }
        }
    }
}

private fun sliderSteps(minimum: Float, maximum: Float, step: Float): Int =
    (((maximum - minimum) / step).roundToInt() - 1).coerceAtLeast(0)
