package com.tvnext.app

import com.tvnext.app.core.common.platform.DesktopAppPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Windows file-backed preferences. Portable data stays beside the executable. */
internal class DesktopStorageContext {
    val applicationContext get()=this
    val filesDir get()=DesktopAppPaths.dataDirectory
    fun getSharedPreferences(name:String,mode:Int):DesktopPreferences {
        require(name.matches(Regex("[A-Za-z0-9_.-]+")))
        val file=File(filesDir,"settings/$name.properties").canonicalFile
        return preferences.computeIfAbsent(file.absolutePath){DesktopPreferences(file)}
    }
    companion object {
        const val MODE_PRIVATE=0
        private val preferences=ConcurrentHashMap<String,DesktopPreferences>()
    }
}
internal class DesktopPreferences(private val file:File) {
    private val values=Properties().apply {if(file.isFile)file.inputStream().use(::load)}
    @Synchronized fun contains(key:String)=values.containsKey(key)
    @Synchronized fun getString(key:String,fallback:String?):String?=values.getProperty(key)?.removePrefix("s:") ?: fallback
    @Synchronized fun getBoolean(key:String,fallback:Boolean)=values.getProperty(key)?.removePrefix("b:")?.toBooleanStrictOrNull() ?: fallback
    @Synchronized fun getInt(key:String,fallback:Int)=values.getProperty(key)?.removePrefix("i:")?.toIntOrNull() ?: fallback
    @Synchronized fun getLong(key:String,fallback:Long)=values.getProperty(key)?.removePrefix("l:")?.toLongOrNull() ?: fallback
    val all:Map<String,Any> @Synchronized get()=values.stringPropertyNames().associateWith {key ->
        val s=values.getProperty(key)
        when(s.take(2)){"b:"->s.drop(2).toBoolean();"i:"->s.drop(2).toInt();"l:"->s.drop(2).toLong();else->s.removePrefix("s:")}
    }
    fun edit()=Editor()
    inner class Editor {
        private val updates=mutableMapOf<String,String?>()
        private var clear=false
        fun putString(key:String,value:String?)=apply {updates[key]=value?.let {"s:$it"}}
        fun putBoolean(key:String,value:Boolean)=apply {updates[key]="b:$value"}
        fun putInt(key:String,value:Int)=apply {updates[key]="i:$value"}
        fun putLong(key:String,value:Long)=apply {updates[key]="l:$value"}
        fun remove(key:String)=apply {updates[key]=null}
        fun clear()=apply {clear=true}
        fun apply(){check(commit()){ "Unable to save TVNext preferences" }}
        fun commit():Boolean=synchronized(this@DesktopPreferences) {
            val next=Properties().apply {if(!clear)putAll(this@DesktopPreferences.values);updates.forEach {(k,v)->if(v==null)remove(k) else setProperty(k,v)}}
            try {
                file.parentFile.mkdirs()
                val tmp=File(file.parentFile,file.name+".tmp")
                tmp.outputStream().use {next.store(it,"TVNext")}
                try {Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
                catch(e:java.nio.file.AtomicMoveNotSupportedException){Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING)}
                values.clear();values.putAll(next);true
            }catch(e:Exception){false}
        }
    }
}
internal data class RecordingLocation(val token:String,val title:String,val root:File)
internal object RecordingStorageLocations {
    fun available(context:DesktopStorageContext):List<RecordingLocation> = buildList {
        add(RecordingLocation("app-storage://internal/recordings","TVNext · recordings",File(context.filesDir,"tvnext-recordings")))
        context.getSharedPreferences("desktop_storage",0).getString("external_recordings",null)?.let {path ->
            val dir=File(path)
            if(dir.parentFile?.isDirectory==true)add(RecordingLocation("desktop-storage://selected","${dir.parentFile.name} · TVNext",dir))
        }
    }
    fun choose(context:DesktopStorageContext,directory:File):Boolean {
        val root=File(directory.canonicalFile,"TVNextRecordings")
        if(!root.isDirectory && !root.mkdirs())return false
        return context.getSharedPreferences("desktop_storage",0).edit().putString("external_recordings",root.absolutePath).commit()
    }
    fun get(context:DesktopStorageContext,token:String)=available(context).firstOrNull {it.token==token}
    fun ownerOf(context:DesktopStorageContext,folder:File)=available(context).firstOrNull {it.root.canonicalFile==folder.canonicalFile.parentFile}
}
