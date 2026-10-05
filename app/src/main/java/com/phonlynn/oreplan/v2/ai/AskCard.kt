package com.phonlynn.oreplan.v2.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonlynn.oreplan.domain.ai.tool.AskAnswer
import com.phonlynn.oreplan.domain.ai.tool.AskQuestion
import com.phonlynn.oreplan.v2.icons.Lucide
import com.phonlynn.oreplan.v2.theme.VColors
import com.phonlynn.oreplan.v2.theme.VText
import com.phonlynn.oreplan.v2.theme.VTypo
import com.phonlynn.oreplan.v2.theme.vPressable

/**
 * 提问卡（设计稿 `tZDQ2`「提问工具 · 多题」）。
 *
 * ## 三道题**连排**，不分页
 *
 * 用户口径。一次填完一起提交，比点三次「下一题」省事，而且能回头看前面答了什么。
 *
 * ## 每题**默认选中第一个选项**
 *
 * 用户口径。所以工具说明里约定「把你认为用户最可能选的放第一位」——
 * 默认值会被用户当成"已经答过了"直接提交，随手排的顺序等于替用户做了个随机决定。
 *
 * ## 「其他」勾了但不输入 = **跳过这题**
 *
 * 用户口径：「可以通过选择其他右侧的复选框，不点击其他的输入实现跳过」。
 * 所以「跳过」不需要额外的按钮，也不需要禁用态 —— 提交永远合法。
 *
 * ## 单选画圆、多选画方
 *
 * 设计稿里三题的勾选框**长得一样**（都是 `r7` 方块），但 Q2/Q3 其实是单选 ——
 * 用户看到方块会以为能多选。这是**实现层面的决定**（不动结构，只换控件画法），
 * 按项目约定属于可以自行决定的范围。
 *
 * @param answers 每题的作答，key 是问题 id
 */
@Composable
fun AskCard(
    questions: List<AskQuestion>,
    answers: Map<String, AskAnswer>,
    onAnswer: (questionId: String, answer: AskAnswer) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(VColors.surface)
            .border(1.dp, VColors.line, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Head(questionCount = questions.size)
        CardDivider()

        questions.forEachIndexed { index, question ->
            if (index > 0) CardDivider()
            QuestionBlock(
                index = index,
                question = question,
                answer = answers[question.id] ?: AskAnswer(chosen = setOf(0)),
                onAnswer = { onAnswer(question.id, it) },
            )
        }

        CardDivider()
        Submit(onClick = onSubmit)
    }
}

/** 卡片头。设计稿 `Q Head 318x26`：徽标 + 「需要你确认」 + 「N 个问题」胶囊。 */
@Composable
private fun Head(questionCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(VColors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                // 设计稿 `Q Badge` 用的是 message-circle（提问），不是工具卡的 sparkles
                Icon(Lucide.MessageCircle, null, Modifier.size(14.dp), tint = VColors.accent)
            }
            VText("需要你确认", VTypo.body.copy(fontWeight = FontWeight.Medium), color = VColors.ink)
        }
        // 状态胶囊显示**待回答问题数量**（用户认可这个语义）
        CardPill("$questionCount 个问题", VColors.amberSoft, VColors.amber)
    }
}

/** 一道题。设计稿 `Q Block gap10`：题号行 + 选项列表。 */
@Composable
private fun QuestionBlock(
    index: Int,
    question: AskQuestion,
    answer: AskAnswer,
    onAnswer: (AskAnswer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(VColors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                VText(
                    "${index + 1}",
                    VTypo.caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = VColors.accent,
                )
            }
            VText(
                question.text,
                VTypo.body.copy(fontWeight = FontWeight.SemiBold),
                color = VColors.ink,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            question.options.forEachIndexed { optionIndex, option ->
                OptionRow(
                    label = option,
                    selected = optionIndex in answer.chosen,
                    round = !question.multiSelect,
                    onClick = {
                        val next = if (question.multiSelect) {
                            // 多选：点一下就切换
                            if (optionIndex in answer.chosen) answer.chosen - optionIndex
                            else answer.chosen + optionIndex
                        } else {
                            // 单选：换一个
                            setOf(optionIndex)
                        }
                        onAnswer(answer.copy(chosen = next))
                    },
                )
            }
            OtherRow(
                selected = answer.other != null,
                text = answer.other.orEmpty(),
                round = !question.multiSelect,
                onToggle = {
                    // 取消勾选 → 回到"没写其他"，此时若选项也为空就是跳过
                    onAnswer(answer.copy(other = if (answer.other == null) "" else null))
                },
                onTextChange = { onAnswer(answer.copy(other = it)) },
            )
        }
    }
}

/**
 * 一个选项行。设计稿 `Option 318x44 r12 pad[12,14]`。
 *
 * 未选：底色 `$bg`、文字 `fs13/normal $ink`、勾选框白底
 * 已选：底色 `$accent-soft`、文字 `fs13/500 $accent`、勾选框 `$accent` + 白勾
 */
@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    round: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) VColors.accentSoft else VColors.bg)
            .vPressable(scaleDown = 0.98f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VText(
            label,
            VTypo.caption.copy(
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (selected) VColors.accent else VColors.ink,
            modifier = Modifier.weight(1f),
        )
        Tick(selected = selected, round = round)
    }
}

/**
 * 「其他（请自行输入）」那一行。
 *
 * 比普通选项多一个输入框；勾选但不输入 = **跳过这题**（用户口径）。
 * 所以没有"取消"之类的额外出口 —— 清空就是跳过。
 */
@Composable
private fun OtherRow(
    selected: Boolean,
    text: String,
    round: Boolean,
    onToggle: () -> Unit,
    onTextChange: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) VColors.accentSoft else VColors.bg)
            .vPressable(scaleDown = 0.98f) {
                // 点整行 = 勾上并聚焦输入框，少一次点击
                if (!selected) onToggle()
                runCatching { focus.requestFocus() }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Lucide.PencilLine, null, Modifier.size(15.dp), tint = VColors.ink3)

        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                // 占位文字自己画，不用 BasicTextField 的 decorationBox ——
                // 这里只有一行，手写更省事也更可控
                VText("其他（请自行输入）", VTypo.caption.copy(fontSize = 13.sp), color = VColors.ink3)
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    color = if (selected) VColors.accent else VColors.ink,
                ),
                cursorBrush = SolidColor(VColors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        }

        Tick(selected = selected, round = round)
    }
}

/**
 * 勾选框。设计稿 `20x20 r7`。
 *
 * **单选画圆、多选画方** —— 形状是唯一能让人一眼分清"这题能不能选多个"的线索，
 * 光靠标题里的「（可多选）」不够。
 */
@Composable
private fun Tick(selected: Boolean, round: Boolean) {
    val shape = if (round) CircleShape else RoundedCornerShape(7.dp)
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(shape)
            .background(if (selected) VColors.accent else VColors.surface)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = if (selected) Color.Transparent else VColors.line,
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(Lucide.Check, null, Modifier.size(12.dp), tint = Color.White)
        }
    }
}

/** 提交。设计稿 `Submit 318x46 $accent r14`，文字 `fs14/700` 白。 */
@Composable
private fun Submit(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(VColors.accent)
            .vPressable(scaleDown = 0.98f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        VText("提交", VTypo.body.copy(fontWeight = FontWeight.Bold), color = Color.White)
    }
}
