package com.anezium.taskerbridge.glasses

import com.anezium.taskerbridge.shared.TaskerTask

data class HudMenuSelection(
    val viewMode: HelperViewMode,
    val selectedIndex: Int,
    val selectedProjectIndex: Int,
    val selectedProjectName: String,
)

data class IndexedTask(
    val index: Int,
    val task: TaskerTask,
)

class HudMenuModel(
    private val state: HelperUiState,
) {
    val projects: List<TaskProject> = state.tasks.taskProjects()
    val selectedTaskIndex: Int =
        if (state.tasks.isEmpty()) 0 else state.selectedIndex.coerceIn(0, state.tasks.lastIndex)
    val safeProjectIndex: Int =
        if (projects.isEmpty()) 0 else state.selectedProjectIndex.coerceIn(0, projects.lastIndex)
    val selectedProject: TaskProject? =
        projects.firstOrNull { it.name == state.selectedProjectName }
            ?: projects.getOrNull(safeProjectIndex)
    val rowCount: Int = when (state.viewMode) {
        HelperViewMode.PROJECTS -> projects.size
        HelperViewMode.TASKS -> selectedProject?.taskIndices?.size ?: 0
    }
    val selectedRowIndex: Int = when (state.viewMode) {
        HelperViewMode.PROJECTS -> safeProjectIndex
        HelperViewMode.TASKS -> selectedProject
            ?.taskIndices
            ?.indexOf(selectedTaskIndex)
            ?.takeIf { it >= 0 }
            ?: 0
    }

    fun selectedTask(): IndexedTask? =
        state.tasks.getOrNull(selectedTaskIndex)?.let { task -> IndexedTask(selectedTaskIndex, task) }

    fun selection(): HudMenuSelection = HudMenuSelection(
        viewMode = state.viewMode,
        selectedIndex = selectedTaskIndex,
        selectedProjectIndex = safeProjectIndex,
        selectedProjectName = selectedProject?.name.orEmpty(),
    )

    fun move(delta: Int): HudMenuSelection = when (state.viewMode) {
        HelperViewMode.PROJECTS -> selectProject(wrapIndex(safeProjectIndex + delta, projects.size))
        HelperViewMode.TASKS -> moveTask(delta)
    }

    fun selectProject(index: Int): HudMenuSelection {
        if (projects.isEmpty()) return selection()
        val safeIndex = index.coerceIn(0, projects.lastIndex)
        return HudMenuSelection(
            viewMode = HelperViewMode.PROJECTS,
            selectedIndex = selectedTaskIndex,
            selectedProjectIndex = safeIndex,
            selectedProjectName = projects[safeIndex].name,
        )
    }

    fun enterProject(index: Int = safeProjectIndex): HudMenuSelection {
        if (projects.isEmpty()) return selection()
        val safeIndex = index.coerceIn(0, projects.lastIndex)
        val project = projects[safeIndex]
        val selectedIndex = state.selectedIndex
            .takeIf { it in project.taskIndices }
            ?: project.taskIndices.first()
        return HudMenuSelection(
            viewMode = HelperViewMode.TASKS,
            selectedIndex = selectedIndex,
            selectedProjectIndex = safeIndex,
            selectedProjectName = project.name,
        )
    }

    fun selectTask(index: Int): HudMenuSelection {
        if (state.tasks.isEmpty()) return selection()
        val safeTaskIndex = index.coerceIn(0, state.tasks.lastIndex)
        val projectIndex = projects.projectIndexFor(state.tasks[safeTaskIndex].projectGroupName())
        return HudMenuSelection(
            viewMode = HelperViewMode.TASKS,
            selectedIndex = safeTaskIndex,
            selectedProjectIndex = projectIndex,
            selectedProjectName = projects.getOrNull(projectIndex)?.name.orEmpty(),
        )
    }

    fun backToProjects(): HudMenuSelection = HudMenuSelection(
        viewMode = HelperViewMode.PROJECTS,
        selectedIndex = selectedTaskIndex,
        selectedProjectIndex = selectedProjectIndexForCurrentTask(),
        selectedProjectName = projects.getOrNull(selectedProjectIndexForCurrentTask())?.name.orEmpty(),
    )

    private fun moveTask(delta: Int): HudMenuSelection {
        val project = selectedProject ?: return selection()
        val localIndex = project.taskIndices
            .indexOf(selectedTaskIndex)
            .takeIf { it >= 0 }
            ?: 0
        return selectTask(project.taskIndices[wrapIndex(localIndex + delta, project.taskIndices.size)])
    }

    private fun selectedProjectIndexForCurrentTask(): Int {
        val taskProjectName = state.tasks.getOrNull(selectedTaskIndex)?.projectGroupName().orEmpty()
        return projects.projectIndexFor(taskProjectName)
    }
}

fun HelperUiState.menuModel(): HudMenuModel = HudMenuModel(this)

fun HelperUiState.applyMenuSelection(selection: HudMenuSelection): HelperUiState = copy(
    viewMode = selection.viewMode,
    selectedIndex = selection.selectedIndex,
    selectedProjectIndex = selection.selectedProjectIndex,
    selectedProjectName = selection.selectedProjectName,
)

fun List<TaskProject>.projectIndexFor(projectName: String): Int =
    indexOfFirst { it.name == projectName }
        .takeIf { it >= 0 }
        ?: 0

private fun wrapIndex(
    index: Int,
    size: Int,
): Int {
    if (size <= 0) return 0
    return ((index % size) + size) % size
}
