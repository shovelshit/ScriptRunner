package com.qa.prefsreset;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 脚本列表适配器：
 * - 一行展示一套 {@link ScriptSet}（名称/来源/任务数/白名单命中数/描述）；
 * - 支持点击展开/折叠，展开后在卡片内追加展示该脚本包含的每一条具体任务；
 * - 每一行提供「执行」按钮，点击后通过回调通知外部（MainActivity）单独触发这一套脚本；
 * - 每一行提供「随开机执行」开关，切换后通过回调通知外部持久化这个选择
 *   （不影响手动执行——任一脚本随时都可以点「执行」按钮手动触发）；
 * - 每一行提供「删除」按钮，点击后通过回调通知外部删除该脚本对应的本地文件。
 */
public class ScriptSetAdapter extends RecyclerView.Adapter<ScriptSetAdapter.ViewHolder> {

    /** 单独执行某一套脚本时的回调 */
    public interface OnRunScriptListener {
        void onRunScript(ScriptSet scriptSet);
    }

    /** 切换某一套脚本的「随开机执行」开关时的回调 */
    public interface OnToggleRunOnBootListener {
        void onToggleRunOnBoot(ScriptSet scriptSet, boolean runOnBoot);
    }

    /** 点击某一套脚本的「删除」按钮时的回调 */
    public interface OnDeleteScriptListener {
        void onDeleteScript(ScriptSet scriptSet);
    }

    private final List<ScriptSet> scriptSets = new ArrayList<>();
    private final Set<String> expandedIds = new HashSet<>();
    private final OnRunScriptListener runListener;
    private final OnToggleRunOnBootListener toggleRunOnBootListener;
    private final OnDeleteScriptListener deleteListener;

    public ScriptSetAdapter(OnRunScriptListener runListener) {
        this(runListener, null, null);
    }

    public ScriptSetAdapter(OnRunScriptListener runListener,
                             OnToggleRunOnBootListener toggleRunOnBootListener) {
        this(runListener, toggleRunOnBootListener, null);
    }

    public ScriptSetAdapter(OnRunScriptListener runListener,
                             OnToggleRunOnBootListener toggleRunOnBootListener,
                             OnDeleteScriptListener deleteListener) {
        this.runListener = runListener;
        this.toggleRunOnBootListener = toggleRunOnBootListener;
        this.deleteListener = deleteListener;
    }

    public void submitList(List<ScriptSet> newList) {
        scriptSets.clear();
        if (newList != null) {
            scriptSets.addAll(newList);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_script_set, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ScriptSet scriptSet = scriptSets.get(position);
        boolean expanded = expandedIds.contains(scriptSet.id);

        holder.name.setText(scriptSet.name);
        holder.meta.setText("来源: " + scriptSet.source
                + " · 任务数 " + scriptSet.tasks.size()
                + " · 白名单命中 " + scriptSet.countRunnableTasks());

        if (scriptSet.description == null || scriptSet.description.isEmpty()) {
            holder.description.setVisibility(View.GONE);
        } else {
            holder.description.setVisibility(View.VISIBLE);
            holder.description.setText(scriptSet.description);
        }

        holder.toggleExpand.setText(expanded ? "收起任务列表 ▴" : "展开任务列表 ▾");
        holder.taskListContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (expanded) {
            bindTaskList(holder.taskListContainer, scriptSet);
        }

        View.OnClickListener toggle = v -> {
            if (expandedIds.contains(scriptSet.id)) {
                expandedIds.remove(scriptSet.id);
            } else {
                expandedIds.add(scriptSet.id);
            }
            notifyItemChanged(holder.getBindingAdapterPosition());
        };
        holder.toggleExpand.setOnClickListener(toggle);
        holder.name.setOnClickListener(toggle);

        holder.runButton.setOnClickListener(v -> {
            if (runListener != null) {
                runListener.onRunScript(scriptSet);
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            if (deleteListener != null) {
                deleteListener.onDeleteScript(scriptSet);
            }
        });

        // 先清空监听器再 setChecked，避免 RecyclerView 复用 ViewHolder 时触发多余的回调
        holder.runOnBootSwitch.setOnCheckedChangeListener(null);
        holder.runOnBootSwitch.setChecked(scriptSet.runOnBoot);
        holder.runOnBootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (toggleRunOnBootListener != null) {
                toggleRunOnBootListener.onToggleRunOnBoot(scriptSet, isChecked);
            }
        });
    }

    private void bindTaskList(LinearLayout container, ScriptSet scriptSet) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(container.getContext());
        if (scriptSet.tasks.isEmpty()) {
            TextView empty = new TextView(container.getContext());
            empty.setText("（该脚本未包含任何任务）");
            empty.setTextSize(11f);
            empty.setTextColor(0xFF999999);
            container.addView(empty);
            return;
        }
        for (ResetTaskConfig task : scriptSet.tasks) {
            boolean runnable = !task.requiresWhitelistCheck() || scriptSet.whitelist.contains(task.packageName);
            View row = inflater.inflate(R.layout.item_task_row, container, false);
            TextView textView = (TextView) row;
            String mark = runnable ? "✓" : "✗(不在白名单)";
            String pkgPart = task.packageName.isEmpty() ? "(无包名，通用命令)" : task.packageName;
            String summaryPart = task.summary.isEmpty() ? "" : (" - " + task.summary);
            textView.setText("[" + mark + "] " + pkgPart + summaryPart
                    + " (共" + task.commands.size() + "条命令, restartApp=" + task.restartApp + ")");
            textView.setTextColor(runnable ? 0xFF333333 : 0xFFB00020);
            container.addView(row);
        }
    }

    @Override
    public int getItemCount() {
        return scriptSets.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView meta;
        final TextView description;
        final TextView toggleExpand;
        final LinearLayout taskListContainer;
        final android.widget.Button runButton;
        final android.widget.Button deleteButton;
        final Switch runOnBootSwitch;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.text_script_name);
            meta = itemView.findViewById(R.id.text_script_meta);
            description = itemView.findViewById(R.id.text_script_description);
            toggleExpand = itemView.findViewById(R.id.text_toggle_expand);
            taskListContainer = itemView.findViewById(R.id.layout_task_list);
            runButton = itemView.findViewById(R.id.button_run_script);
            deleteButton = itemView.findViewById(R.id.button_delete_script);
            runOnBootSwitch = itemView.findViewById(R.id.switch_run_on_boot);
        }
    }
}
