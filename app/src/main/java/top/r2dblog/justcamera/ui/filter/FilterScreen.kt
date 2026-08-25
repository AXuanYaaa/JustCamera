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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.r2dblog.justcamera.R
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
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.back),
                )
            }
            Column {
                Text(
                    stringResource(R.string.filter_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.filter_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Text(
                stringResource(R.string.filter_phase_notice),
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.filter_builtin), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        descriptors.forEach { descriptor ->
            val selectedColor = if (descriptor.id == selected.id) {
                MaterialTheme.colorScheme.primaryContainer
            } else MaterialTheme.colorScheme.surface
            Surface(
                color = selectedColor,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable { selected = descriptor },
            ) {
                Text(
                    filterName(descriptor),
                    modifier = Modifier.padding(14.dp),
                    color = if (descriptor.id == selected.id) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        FilterEditor(
            descriptor = selected,
            parameters = parameters,
            enabled = enabled,
            onEnabledChange = { enabled = it },
            onParametersChange = { parameters = it },
            onReset = { parameters = FilterParameters.defaults(selected) },
        )
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.filter_reference_looks), fontWeight = FontWeight.Bold)
        BuiltInPresets.all.forEach { preset ->
            Text(
                "${presetName(preset.id)} · " +
                    stringResource(R.string.filter_operations, preset.chain.operations.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
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
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(filterName(descriptor), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.filter_enabled),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.filter_reset),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onReset).padding(12.dp),
                    )
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
            }
            descriptor.parameterSpecs.forEach { spec ->
                ParameterControl(spec, parameters) { value ->
                    onParametersChange(
                        parameters.copy(values = parameters.values + (spec.key to value)),
                    )
                }
            }
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
                    stringResource(
                        R.string.filter_parameter_value,
                        parameterName(spec.key),
                        String.format(Locale.getDefault(), "%.2f", value),
                    ),
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
                Text(
                    stringResource(
                        R.string.filter_parameter_value,
                        parameterName(spec.key),
                        value.toString(),
                    ),
                )
                Slider(
                    value = value.toFloat(),
                    onValueChange = {
                        val stepCount = ((it - spec.minimum) / spec.step).roundToInt()
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
                Text(parameterName(spec.key))
                Switch(
                    checked = parameters.boolean(spec.key, spec.default),
                    onCheckedChange = { onValueChange(FilterParameterValue.BooleanValue(it)) },
                )
            }
            is FilterParameterSpec.EnumParameter -> {
                val value = parameters.enum(spec.key, spec.default)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val index = spec.allowedValues.indexOf(value).coerceAtLeast(0)
                            val next = spec.allowedValues[(index + 1) % spec.allowedValues.size]
                            onValueChange(FilterParameterValue.EnumValue(next))
                        }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(parameterName(spec.key))
                    Text(value, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun filterName(descriptor: FilterDescriptor): String {
    val resource = when (descriptor.id) {
        "builtin.exposure" -> R.string.filter_exposure
        "builtin.contrast" -> R.string.filter_contrast
        "builtin.saturation" -> R.string.filter_saturation
        "builtin.temperature_tint" -> R.string.filter_temperature_tint
        "builtin.highlights_shadows" -> R.string.filter_highlights_shadows
        "builtin.fade" -> R.string.filter_fade
        "builtin.vignette" -> R.string.filter_vignette
        else -> return descriptor.displayName
    }
    return stringResource(resource)
}

@Composable
private fun parameterName(key: String): String = stringResource(
    when (key) {
        "exposure" -> R.string.filter_parameter_exposure
        "contrast" -> R.string.filter_parameter_contrast
        "saturation" -> R.string.filter_parameter_saturation
        "temperature" -> R.string.filter_parameter_temperature
        "tint" -> R.string.filter_parameter_tint
        "highlights" -> R.string.filter_parameter_highlights
        "shadows" -> R.string.filter_parameter_shadows
        "fade" -> R.string.filter_parameter_fade
        "vignette" -> R.string.filter_parameter_vignette
        "strength" -> R.string.filter_parameter_strength
        else -> R.string.filter_parameter_strength
    },
)

@Composable
private fun presetName(id: String): String = stringResource(
    when (id) {
        "builtin.neutral" -> R.string.preset_neutral
        "builtin.soft_contrast" -> R.string.preset_soft_contrast
        "builtin.warm_film" -> R.string.preset_warm_film
        "builtin.clean_bw" -> R.string.preset_clean_bw
        else -> R.string.preset_cinematic_soft
    },
)

private fun sliderSteps(minimum: Float, maximum: Float, step: Float): Int =
    (((maximum - minimum) / step).roundToInt() - 1).coerceAtLeast(0)
