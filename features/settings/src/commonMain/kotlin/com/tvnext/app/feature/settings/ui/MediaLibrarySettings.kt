package com.tvnext.app.feature.settings.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tvnext.app.feature.settings.domain.*
import com.tvnext.app.core.ui.component.TvCardButton
import com.tvnext.app.core.ui.i18n.uiText

fun MediaPosterSize.mediaLabel()=uiText(when(this){MediaPosterSize.SMALL->"Маленькие";MediaPosterSize.MEDIUM->"Средние";MediaPosterSize.LARGE->"Крупные"})
fun MediaFilmSort.mediaLabel()=uiText(when(this){MediaFilmSort.PROVIDER->"Как у провайдера";MediaFilmSort.NAME_ASC->"А–Я";MediaFilmSort.NAME_DESC->"Я–А"})
fun MediaCategorySort.mediaLabel()=uiText(if(this==MediaCategorySort.PROVIDER)"Как у провайдера" else "По алфавиту")
fun MediaResumeMode.mediaLabel()=uiText(when(this){MediaResumeMode.CONTINUE->"Продолжать с сохранённого места";MediaResumeMode.ASK->"Спрашивать";MediaResumeMode.START_OVER->"Всегда с начала"})

@Composable
fun MediaLibrarySettings(preferences:MediaPreferences,onChange:(MediaPreferences)->Unit,compact:Boolean=false,showTitle:Boolean=true) {
    var choice by remember {mutableStateOf<Pair<String,List<Pair<String,()->Unit>>>?>(null)}
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(showTitle)Text(uiText("Медиатека"),style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onSurface)
        @Composable fun row(label:String,value:String,click:()->Unit) {
            TvCardButton(onClick=click,modifier=Modifier.fillMaxWidth().heightIn(min=if(compact)38.dp else 50.dp),shape=RoundedCornerShape(4.dp),
                contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text(uiText(label),color=MaterialTheme.colorScheme.onSurface,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                    Text(value,color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f),textAlign=TextAlign.End)
                }
            }
        }
        row("Размер постеров",preferences.posterSize.mediaLabel()) {choice=uiText("Размер постеров") to MediaPosterSize.entries.map {it.mediaLabel() to {onChange(preferences.copy(posterSize=it));Unit}}}
        row("Сортировка фильмов",preferences.filmSort.mediaLabel()) {choice=uiText("Сортировка фильмов") to MediaFilmSort.entries.map {it.mediaLabel() to {onChange(preferences.copy(filmSort=it));Unit}}}
        row("Порядок категорий",preferences.categorySort.mediaLabel()) {choice=uiText("Порядок категорий") to MediaCategorySort.entries.map {it.mediaLabel() to {onChange(preferences.copy(categorySort=it));Unit}}}
        row("Запоминать место в каталоге",uiText(if(preferences.rememberPosition)"Включено" else "Выключено")) {onChange(preferences.copy(rememberPosition=!preferences.rememberPosition))}
        row("Начало просмотра",preferences.resumeMode.mediaLabel()) {choice=uiText("Начало просмотра") to MediaResumeMode.entries.map {it.mediaLabel() to {onChange(preferences.copy(resumeMode=it));Unit}}}
    }
    choice?.let {selection->AlertDialog(onDismissRequest={choice=null},title={Text(selection.first)},text={
        Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            selection.second.forEach {(label,action)->TvCardButton(onClick={action();choice=null},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(4.dp)){Text(label,color=MaterialTheme.colorScheme.onSurface)}}
        }
    },confirmButton={})}
}
