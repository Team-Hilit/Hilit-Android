package stories.components.designsystem.hilitasynctextfield

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dminus14.catalog.controls.CatalogControls
import com.dminus14.designsystem.component.textfield.HilitAsyncTextField
import com.dminus14.designsystem.component.textfield.HilitAsyncTextFieldType

private val PreviewSurfaceColor = Color(0xFF4A4B50)

@CatalogControls
@Composable
internal fun HilitAsyncTextFieldCatalogAdapter(
    value: String,
    placeholder: String,
    processingText: String,
    subText: String,
    type: HilitAsyncTextFieldType,
    descriptionText: String = "",
) {
    var text by remember(value) { mutableStateOf(value) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(PreviewSurfaceColor)
                .padding(16.dp),
    ) {
        HilitAsyncTextField(
            value = text,
            onValueChange = { text = it },
            type = type,
            placeholder = placeholder,
            processingText = processingText,
            subText = subText,
            descriptionText = descriptionText,
            onClearClick = { text = "" },
        )
    }
}
