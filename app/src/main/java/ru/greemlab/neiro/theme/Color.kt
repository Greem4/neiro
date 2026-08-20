package ru.greemlab.neiro.theme

import androidx.compose.ui.graphics.Color

// --- Тёмная тема ---
// Фон — истинно чёрный: на амоледе это выключенные пиксели, а не серое пятно.
// Прежний #121212 на таком экране читался мутной подложкой, и панели поверх
// него сливались в кашу из близких тёмно-серых.
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF1E1E1E)
val PrimaryDark = Color(0xFF90CAF9)
val OnPrimaryDark = Color(0xFF003258)
val PrimaryContainerDark = Color(0xFF00497D)
val OnPrimaryContainerDark = Color(0xFFD1E4FF)
val SecondaryDark = Color(0xFFB7C9FF)
val OnSecondaryDark = Color(0xFF1F306C)
val SecondaryContainerDark = Color(0xFF394983)
val OnSecondaryContainerDark = Color(0xFFDEE0FF)
val TertiaryDark = Color(0xFFFFB68A)
val OnTertiaryDark = Color(0xFF552105)
val TertiaryContainerDark = Color(0xFF733619)
val OnTertiaryContainerDark = Color(0xFFFFDBCB)
val ErrorDark = Color(0xFFFFB4AB)

// Нейтральные поверхности тёмной темы (без фиолетового оттенка дефолтов M3).
val SurfaceVariantDark = Color(0xFF2E333A)
val OnSurfaceVariantDark = Color(0xFFC0C7D0)
val OutlineDark = Color(0xFF8A939E)
val OutlineVariantDark = Color(0xFF454B54)
val OnSurfaceDark = Color(0xFFE3E6EA)

// --- Светлая тема ---
val LightBackground = Color(0xFFF8F9FA)
val LightSurface = Color(0xFFFFFFFF)
val PrimaryLight = Color(0xFF1976D2)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFD1E4FF)
val OnPrimaryContainerLight = Color(0xFF001D36)
val SecondaryLight = Color(0xFF515E9C)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFDEE0FF)
val OnSecondaryContainerLight = Color(0xFF09175A)
val TertiaryLight = Color(0xFF8C4F2F)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFFFDBCB)
val OnTertiaryContainerLight = Color(0xFF341101)
val ErrorLight = Color(0xFFB3261E)

// Нейтральные поверхности светлой темы (в семействе фона #F8F9FA).
val SurfaceVariantLight = Color(0xFFE2E7EE)
val OnSurfaceVariantLight = Color(0xFF47525E)
val OutlineLight = Color(0xFF77818D)
val OutlineVariantLight = Color(0xFFC6CDD6)
val OnSurfaceLight = Color(0xFF1C1B1F)

// --- Градиент логотипа (бренд-знак, темы не имеет) ---
val LogoGradientStart = Color(0xFF24A1DE)
val LogoGradientEnd = Color(0xFF1E96C8)

// --- Семантические цвета приложения ---
// Все текстовые семантические цвета существуют в двух вариантах: `*Light` —
// для светлой темы (контраст на белом ≥ 4.5), `*Dark` — для тёмной (пастель).
// Напрямую их не используют: читают из [LocalNeiroSemanticColors], чтобы
// цвет следовал выбранной в приложении теме, а не системной.

/** «Прибыль» / «пришёл и оплатил» — персиковый. */
val ProfitLight = Color(0xFF9A5B10)
val ProfitDark = Color(0xFFFFAD80)

/** «Подтвердил» (галочка, что придёт) — лавандовый. */
val ExpectedLight = Color(0xFF3949AB)
val ExpectedDark = Color(0xFFA7B2FF)

/** Цвет шапки записи (время и иконка) — зелёный. */
val ScheduleHeaderLight = Color(0xFF2E7D32)
val ScheduleHeaderDark = Color(0xFF4CAF50)

/** «Ожидается» — мятный. */
val StatusExpectedLight = Color(0xFF00796B)
val StatusExpectedDark = Color(0xFFB2DFDB)

/** Отмена (минус). */
val StatusCancelledLight = Color(0xFFD32F2F)
val StatusCancelledDark = Color(0xFFF44336)

/** Имя диагностики в слоте расписания — читается на обеих темах. */
val DiagnosticsIndigo = Color(0xFF5C6BC0)

/**
 * Подпись выходного дня в шапке календаря. Свой цвет, а не `tertiary`:
 * тот в тёмной теме почти совпадает с персиковым [ProfitDark], и «Сб/Вс»
 * читались как сумма заработанного.
 */
val WeekendLight = Color(0xFFC62828)
val WeekendDark = Color(0xFFEF9A9A)

/** Линия текущего времени на шкале дня — одинакова в обеих темах. */
val NowLineRed = Color(0xFFE53935)

/**
 * Подложка круглой иконки статуса. Белая в обеих темах намеренно: цветная
 * иконка («пришёл», «отменён») читается только на светлом кружке, а сам
 * кружок лежит на приглушённой плашке слота.
 */
val StatusIconSurface = Color(0xFFFFFFFF)

/** Перенос — акцент in-app уведомлений на тёмном фоне карточки. */
val RescheduleNotificationDark = Color(0xFFFFB74D)

/** Перенос — акцент in-app уведомлений на светлом фоне карточки. */
val RescheduleNotificationLight = Color(0xFFE65100)

/** Фирменный жёлтый YClients — используется для кнопки входа/синхронизации с YClients. */
val YClientsYellow = Color(0xFFFFCD00)

/** Контрастный тёмный цвет поверх [YClientsYellow] (текст, иконки). */
val OnYClientsYellow = Color(0xFF1A1A1A)
