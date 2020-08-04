/*
Copyright (C) 2014, 2016, 2017, 2019-2020 Boris Timofeev

This file is part of UniPatcher.

UniPatcher is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

UniPatcher is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with UniPatcher.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.emunix.unipatcher.ui.fragment

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.apache.commons.io.FilenameUtils
import org.emunix.unipatcher.Action
import org.emunix.unipatcher.R
import org.emunix.unipatcher.Settings
import org.emunix.unipatcher.Utils.dpToPx
import org.emunix.unipatcher.Utils.isArchive
import org.emunix.unipatcher.Utils.startForegroundService
import org.emunix.unipatcher.WorkerService
import org.emunix.unipatcher.databinding.ApplyPatchFragmentBinding
import org.emunix.unipatcher.ui.activity.FilePickerActivity
import org.emunix.unipatcher.ui.activity.MainActivity
import timber.log.Timber
import java.io.File

class ApplyPatchFragment : ActionFragment(), View.OnClickListener {

    private var romPath: String = ""
    private var patchPath: String = ""
    private var outputPath: String = ""

    private var _binding: ApplyPatchFragmentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = ApplyPatchFragmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activity?.setTitle(R.string.nav_apply_patch)
        binding.patchCardView.setOnClickListener(this)
        binding.romCardView.setOnClickListener(this)
        binding.outputCardView.setOnClickListener(this)
        parseArgument()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState != null) {
            romPath = savedInstanceState.getString("romPath") ?: ""
            patchPath = savedInstanceState.getString("patchPath") ?: ""
            outputPath = savedInstanceState.getString("outputPath") ?: ""
            if (romPath.isNotEmpty())
                binding.romNameTextView.text = File(romPath).name
            if (patchPath.isNotEmpty())
                binding.patchNameTextView.text = File(patchPath).name
            if (outputPath.isNotEmpty())
                binding.outputNameTextView.text = File(outputPath).name
        }
    }

    private fun parseArgument() {
        patchPath = (activity as MainActivity?)?.arg ?: ""
        if (patchPath != "") {
            binding.patchNameTextView.text = File(patchPath).name
        }
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putString("romPath", romPath)
        savedInstanceState.putString("patchPath", patchPath)
        savedInstanceState.putString("outputPath", outputPath)
    }

    override fun onClick(view: View) {
        val intent = Intent(activity, FilePickerActivity::class.java)
        when (view.id) {
            R.id.patchCardView -> {
                intent.putExtra("title", getString(R.string.file_picker_activity_title_select_patch))
                intent.putExtra("directory", Settings.getPatchDir())
                startActivityForResult(intent, Action.SELECT_PATCH_FILE)
            }
            R.id.romCardView -> {
                intent.putExtra("title", getString(R.string.file_picker_activity_title_select_rom))
                intent.putExtra("directory", Settings.getRomDir())
                startActivityForResult(intent, Action.SELECT_ROM_FILE)
            }
            R.id.outputCardView -> renameOutputRom()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Timber.d("onActivityResult($requestCode, $resultCode, $data)")
        if (resultCode == Activity.RESULT_OK) {
            val path = data?.getStringExtra("path") ?: ""
            if (path.isBlank()) {
                Toast.makeText(activity, R.string.main_activity_toast_file_manager_did_not_return_file_path, Toast.LENGTH_LONG).show()
                return
            }
            if (isArchive(path)) {
                Toast.makeText(activity, R.string.main_activity_toast_archives_not_supported, Toast.LENGTH_LONG).show()
            }
            val filePath = File(path)
            val dir = filePath.parent
            when (requestCode) {
                Action.SELECT_ROM_FILE -> {
                    romPath = path
                    binding.romNameTextView.visibility = View.VISIBLE
                    binding.romNameTextView.text = filePath.name
                    if(dir != null) {
                        Settings.setLastRomDir(dir)
                    }
                    outputPath = makeOutputPath(path)
                    binding.outputNameTextView.text = File(outputPath).name
                }
                Action.SELECT_PATCH_FILE -> {
                    patchPath = path
                    binding.patchNameTextView.visibility = View.VISIBLE
                    binding.patchNameTextView.text = filePath.name
                    if(dir != null) {
                        Settings.setLastPatchDir(dir)
                    }
                }
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun makeOutputPath(fullname: String): String {
        var dir = Settings.getOutputDir()
        if (dir == "") { // get ROM directory
            dir = FilenameUtils.getFullPath(fullname)
        }
        val baseName = FilenameUtils.getBaseName(fullname)
        val ext = FilenameUtils.getExtension(fullname)
        return FilenameUtils.concat(dir, "$baseName [patched].$ext")
    }

    override fun runAction(): Boolean {
        when {
            romPath.isEmpty() && patchPath.isEmpty() -> {
                Toast.makeText(activity, getString(R.string.main_activity_toast_rom_and_patch_not_selected), Toast.LENGTH_LONG).show()
                return false
            }
            romPath.isEmpty() -> {
                Toast.makeText(activity, getString(R.string.main_activity_toast_rom_not_selected), Toast.LENGTH_LONG).show()
                return false
            }
            patchPath.isEmpty() -> {
                Toast.makeText(activity, getString(R.string.main_activity_toast_patch_not_selected), Toast.LENGTH_LONG).show()
                return false
            }
            else -> {
                val intent = Intent(activity, WorkerService::class.java)
                intent.putExtra("action", Action.APPLY_PATCH)
                intent.putExtra("romPath", romPath)
                intent.putExtra("patchPath", patchPath)
                intent.putExtra("outputPath", outputPath)
                startForegroundService(requireActivity(), intent)
                Toast.makeText(activity, R.string.toast_patching_started_check_notify, Toast.LENGTH_SHORT).show()
                return true
            }
        }
    }

    private fun renameOutputRom() {
        if (romPath.isEmpty()) {
            Toast.makeText(activity, getString(R.string.main_activity_toast_rom_not_selected), Toast.LENGTH_LONG).show()
            return
        }
        val renameDialog = AlertDialog.Builder(requireActivity())
        renameDialog.setTitle(R.string.dialog_rename_title)
        val input = EditText(activity)
        input.setText(binding.outputNameTextView.text)
        // add left and right margins to EditText.
        val container = FrameLayout(requireActivity())
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val dp24 = dpToPx(requireActivity(), 24)
        params.setMargins(dp24, 0, dp24, 0)
        input.layoutParams = params
        container.addView(input)
        renameDialog.setView(container)
        renameDialog.setPositiveButton(R.string.dialog_rename_ok, DialogInterface.OnClickListener { _, _ ->
            var newName = input.text.toString()
            if (newName == "") {
                Toast.makeText(activity, R.string.dialog_rename_error_empty_name, Toast.LENGTH_LONG).show()
                return@OnClickListener
            }
            if (newName.contains("/")) {
                newName = newName.replace("/".toRegex(), "_")
                Toast.makeText(activity, R.string.dialog_rename_error_invalid_chars, Toast.LENGTH_LONG).show()
            }
            val newPath = File(outputPath).parent + File.separator + newName
            if (FilenameUtils.equals(newPath, romPath)) {
                Toast.makeText(activity, R.string.dialog_rename_error_same_name, Toast.LENGTH_LONG).show()
                return@OnClickListener
            }
            binding.outputNameTextView.text = newName
            outputPath = newPath
        })
        renameDialog.setNegativeButton(R.string.dialog_rename_cancel) { dialog, _ -> dialog.cancel() }
        renameDialog.show()
    }
}