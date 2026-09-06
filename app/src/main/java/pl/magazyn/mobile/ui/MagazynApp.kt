package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagazynApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route.orEmpty()
    val isFocusedScreen = currentRoute == "note-review" || currentRoute == "tasks-new" || currentRoute.startsWith("shipyards/")
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showQuickAdd by remember { mutableStateOf(false) }
    val drawerDestinations = listOf(
        Destination("tasks", "Zadania", Icons.Default.TaskAlt),
        Destination("orders", "Zamówienia", Icons.Default.Checklist),
        Destination("operations", "Operacje", Icons.Default.SwapVert),
        Destination("inventory", "Inwentaryzacja", Icons.Default.FactCheck),
        Destination("shipyards", "Stocznie", Icons.Default.Business),
        Destination("people", "Osoby", Icons.Default.People),
        Destination("products", "Przedmioty", Icons.Default.Inventory2),
        Destination("data-exchange", "Import i eksport danych", Icons.Default.ImportExport),
        Destination("learning-rules", "Uczenie offline", Icons.Default.Psychology),
        Destination("updates", "Aktualizacje", Icons.Default.SystemUpdate),
    )
    val aiDestination = Destination("ai-settings", "Ustawienia AI", Icons.Default.AutoAwesome)

    fun goTo(route: String) {
        navController.navigate(route) {
            popUpTo("home")
            launchSingleTop = true
        }
        scope.launch { drawerState.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !isFocusedScreen,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxSize()) {
                    Text("Magazyn Mobile", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp))
                    HorizontalDivider()
                    drawerDestinations.forEach { destination ->
                        NavigationDrawerItem(
                            label = { Text(destination.label) },
                            icon = { Icon(destination.icon, null) },
                            selected = backStack?.destination?.route == destination.route,
                            onClick = { goTo(destination.route) },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text(aiDestination.label) },
                        icon = { Icon(aiDestination.icon, null) },
                        selected = backStack?.destination?.route == aiDestination.route,
                        onClick = { goTo(aiDestination.route) },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            modifier = modifier,
            bottomBar = {
                if (!isFocusedScreen) NavigationBar {
                    NavigationBarItem(
                        selected = backStack?.destination?.route == "home",
                        onClick = { goTo("home") },
                        icon = { Icon(Icons.Default.Home, null) },
                        label = { Text("Start") },
                    )
                    NavigationBarItem(
                        selected = backStack?.destination?.route in listOf("find-issue", "search", "issue"),
                        onClick = { goTo("find-issue") },
                        icon = { Icon(Icons.Default.Search, null) },
                        label = { Text("Znajdź/Wydaj") },
                    )
                    FloatingActionButton(onClick = { showQuickAdd = true }) { Icon(Icons.Default.Add, "Dodaj") }
                    NavigationBarItem(
                        selected = backStack?.destination?.route == "history",
                        onClick = { goTo("history") },
                        icon = { Icon(Icons.Default.History, null) },
                        label = { Text("Historia") },
                    )
                    NavigationBarItem(
                        selected = drawerState.isOpen,
                        onClick = { scope.launch { drawerState.open() } },
                        icon = { Icon(Icons.Default.Menu, null) },
                        label = { Text("Menu") },
                    )
                }
            },
        ) { padding ->
            AppNavigation(navController, padding, homeViewModel)
        }
    }

    if (showQuickAdd) {
        ModalBottomSheet(onDismissRequest = { showQuickAdd = false }) {
            QuickAddSheet(
                onClose = { showQuickAdd = false },
                onNote = { showQuickAdd = false; navController.navigate("home") },
                onPeople = { showQuickAdd = false; navController.navigate("people/new") },
                onProducts = { showQuickAdd = false; navController.navigate("products/new") },
                onTask = { showQuickAdd = false; navController.navigate("tasks-new") },
            )
        }
    }
}

@Composable
private fun AppNavigation(navController: NavHostController, padding: PaddingValues, homeViewModel: HomeViewModel) {
    val combinedScreen: @Composable () -> Unit = {
        SearchScreen(
            contentPadding = padding,
            onPerson = { navController.navigate("people/view/$it") },
            onIssuePerson = { navController.navigate("people/issue/$it") },
            onProduct = { navController.navigate("products/view/$it") },
            onShipyard = { id -> navController.navigate(if (id == null) "shipyards" else "shipyards/$id") },
        )
    }
    NavHost(navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                contentPadding = padding,
                onOrders = { navController.navigate("orders") },
                onTasks = { navController.navigate("tasks") },
                onReview = { navController.navigate("note-review") },
                onQuickIssue = { navController.navigate("find-issue") },
                onPerson = { navController.navigate("people/view/$it") },
                onProduct = { navController.navigate("products/view/$it") },
                viewModel = homeViewModel,
            )
        }
        composable("note-review") {
            ParsedNoteReviewScreen(
                contentPadding = padding,
                onBack = { navController.popBackStack() },
                viewModel = homeViewModel,
            )
        }
        composable("find-issue") { combinedScreen() }
        composable("search") { combinedScreen() }
        composable("issue") { combinedScreen() }
        composable("orders") { OrdersScreen(contentPadding = padding) }
        composable("tasks") { TasksScreen(contentPadding = padding, onNewTask = { navController.navigate("tasks-new") }) }
        composable("tasks-new") { TasksScreen(contentPadding = padding, startAdding = true, onCloseEditor = { navController.popBackStack() }) }
        composable("operations") { OperationsScreen(contentPadding = padding) }
        composable("inventory") { InventoryScreen(contentPadding = padding) }
        composable("products") { ProductsScreen(contentPadding = padding) }
        composable("products/new") { ProductsScreen(contentPadding = padding, startAdding = true) }
        composable("products/view/{productId}") { entry -> ProductsScreen(contentPadding = padding, initialProductId = entry.arguments?.getString("productId")) }
        composable("people") { PeopleScreen(contentPadding = padding) }
        composable("people/new") { PeopleScreen(contentPadding = padding, startAdding = true) }
        composable("people/view/{personId}") { entry -> PeopleScreen(contentPadding = padding, initialPersonId = entry.arguments?.getString("personId")) }
        composable("people/issue/{personId}") { entry -> PeopleScreen(contentPadding = padding, initialPersonId = entry.arguments?.getString("personId"), startIssuing = true) }
        composable("history") { HistoryScreen(contentPadding = padding) }
        composable("data-exchange") { DataExchangeScreen(contentPadding = padding) }
        composable("shipyards") {
            ShipyardsScreen(contentPadding = padding, onShipyard = { navController.navigate("shipyards/$it") })
        }
        composable("shipyards/{shipyardId}") { entry ->
            ShipyardsScreen(
                contentPadding = padding,
                initialShipyardId = entry.arguments?.getString("shipyardId"),
                onBack = { navController.popBackStack() },
            )
        }
        composable("shipyard-stock") { ShipyardsScreen(contentPadding = padding, onShipyard = { navController.navigate("shipyards/$it") }) }
        composable("ai-settings") { AiSettingsScreen(contentPadding = padding, onBack = { navController.popBackStack() }) }
        composable("learning-rules") { LearningRulesScreen(contentPadding = padding) }
        composable("updates") { UpdateScreen(contentPadding = padding) }
    }
}
