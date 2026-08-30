package br.com.redesurftank.havalclimatecontrol;

/**
 * Chaves das propriedades do IIntelligentVehicleControlService.
 *
 * <p>Fonte: {@code CarConstants.java} do haval-app-tool-multimidia 1.0.0.79, que é o
 * catálogo extraído do OEM. Antes essas strings viviam duplicadas — constantes privadas
 * no {@code ClimateControlService} e literais soltos na UI — e um erro de digitação num
 * dos lados só aparecia em runtime, como propriedade que nunca atualiza.
 *
 * <p>Cuidado com {@link #INTELLIGENT_SWITCH} e {@link #INTELLIGENT_TEMP_RANGE}: o OEM
 * escreve essas duas com <b>I maiúsculo</b>. Não "corrija".
 */
public final class CarProps {

    private CarProps() {}

    // ── básico ────────────────────────────────────────────────────────────────
    public static final String INSIDE_TEMP   = "car.basic.inside_temp";
    public static final String OUTSIDE_TEMP  = "car.basic.outside_temp";

    // ── HVAC · estado principal ───────────────────────────────────────────────
    public static final String POWER_MODE      = "car.hvac.power_mode";
    public static final String AUTO_ENABLE     = "car.hvac.auto_enable";
    public static final String AC_ENABLE       = "car.hvac.ac_enable";
    public static final String ACMAX_ENABLE    = "car.hvac.acmax_enable";
    public static final String HEATING         = "car.hvac.heating_enable";
    /** "AC Inteligente" do OEM. Só leitura para nós — quem liga/desliga o controle
     *  automático do app é a flag local, não esta propriedade. */
    public static final String INTELLIGENT_SWITCH = "car.hvac.Intelligent_switch_enable";

    // ── HVAC · temperatura ────────────────────────────────────────────────────
    public static final String DRIVER_TEMP     = "car.hvac.driver_temperature";
    public static final String PASS_TEMP       = "car.hvac.pass_temperature";
    public static final String SYNC_ENABLE     = "car.hvac.sync_enable";
    public static final String FRONT_TEMP_RANGE = "car.hvac.front_temperature_range";
    public static final String INTELLIGENT_TEMP_RANGE = "car.hvac.Intelligent_temperature_range";

    // ── HVAC · ar ─────────────────────────────────────────────────────────────
    public static final String FAN_SPEED       = "car.hvac.fan_speed";
    /** Teto da seekbar do ventilador. O OEM não garante 7 — lê daqui. */
    public static final String FAN_SPEED_RANGE = "car.hvac.fan_speed_range";
    /** Distribuição de ar: rosto / rosto+pés / pés / pés+desembaçador. */
    public static final String BLOWER_MODE     = "car.hvac.blower_mode";
    /** Recirculação × ar externo (o botão "troca de ar" do OEM é o mesmo eixo). */
    public static final String CYCLE_MODE      = "car.hvac.cycle_mode";
    public static final String FRONT_DEFROST   = "car.hvac.front_defrost_enable";
    public static final String REAR_DEFROST    = "car.hvac.rear_defrost_enable";

    // ── HVAC · qualidade do ar ────────────────────────────────────────────────
    public static final String PM25            = "car.hvac.pm2.5_value";
    public static final String AQS_ENABLE      = "car.hvac.aqs_enable";
    public static final String ANION_ENABLE    = "car.hvac.anion_enable";

    // ── HVAC · configurações ──────────────────────────────────────────────────
    public static final String COMFORT_CURVE   = "car.hvac.setting.comfort_curve";
    public static final String LIMIT_ENABLE    = "car.hvac.setting.limit_enable";
    public static final String AUTO_DEFROST    = "car.hvac.setting.auto_defrost_enable";

    // ── fragrância (mora em car.basic, não em car.hvac) ───────────────────────
    public static final String FRAGRANCE_STATUS        = "car.basic.fragrance_status";
    public static final String FRAGRANCE_CONCENTRATION = "car.basic.fragrance_concentration";
    public static final String FRAGRANCE_TYPE          = "car.basic.fragrance_type";

    // ── bancos ────────────────────────────────────────────────────────────────
    public static final String DRIVER_SEAT_VENT    = "car.comfort_setting.driver_seat_ventilation_level";
    public static final String PASSENGER_SEAT_VENT = "car.comfort_setting.passenger_seat_ventilation_level";

    // ── EV ────────────────────────────────────────────────────────────────────
    public static final String WADE_MODE = "car.ev.setting.wade_mode_enable";

    /** Tudo que assinamos no addListenerKey/fetchDatas. */
    public static final String[] ALL = {
        INSIDE_TEMP, OUTSIDE_TEMP,
        POWER_MODE, AUTO_ENABLE, AC_ENABLE, ACMAX_ENABLE, HEATING, INTELLIGENT_SWITCH,
        DRIVER_TEMP, PASS_TEMP, SYNC_ENABLE, FRONT_TEMP_RANGE, INTELLIGENT_TEMP_RANGE,
        FAN_SPEED, FAN_SPEED_RANGE, BLOWER_MODE, CYCLE_MODE, FRONT_DEFROST, REAR_DEFROST,
        PM25, AQS_ENABLE, ANION_ENABLE,
        COMFORT_CURVE, LIMIT_ENABLE, AUTO_DEFROST,
        FRAGRANCE_STATUS, FRAGRANCE_CONCENTRATION, FRAGRANCE_TYPE,
        DRIVER_SEAT_VENT, PASSENGER_SEAT_VENT,
        WADE_MODE,
    };
}
