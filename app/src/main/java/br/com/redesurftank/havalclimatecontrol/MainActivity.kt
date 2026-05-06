package br.com.redesurftank.havalclimatecontrol

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.com.redesurftank.havalclimatecontrol.services.ClimateControlService
import br.com.redesurftank.havalclimatecontrol.ui.theme.HavalClimateControlTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HavalClimateControlTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ClimateControlScreen(
                        onStartService = {
                            startForegroundService(Intent(this, ClimateControlService::class.java))
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ClimateControlScreen(onStartService: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Controle Climático Haval",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "O serviço de controle automático do AC monitora a temperatura interna " +
                    "e liga/desliga o AC conforme necessário quando o modo Automático está ativo.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "• AC desliga quando temperatura ≤ setpoint − 0,5°C\n" +
                    "• AC liga quando temperatura ≥ setpoint + 0,5°C\n" +
                    "• Somente ativo quando o AC está em modo Automático",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onStartService) {
            Text("Iniciar Serviço")
        }
    }
}
